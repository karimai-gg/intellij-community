// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.varScopeCanBeNarrowed;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.*;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.controlFlow.*;
import com.intellij.psi.search.searches.SuperMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.JavaRefactoringFactory;
import com.intellij.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.refactoring.util.CommonJavaInlineUtil;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ObjectUtils;
import com.intellij.util.VisibilityUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.MethodUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class ParameterCanBeLocalInspection extends AbstractBaseJavaLocalInspectionTool {
  public static final @NonNls String SHORT_NAME = "ParameterCanBeLocal";

  @Override
  public @NotNull String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.class.structure");
  }

  @Override
  public @NotNull String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public ProblemDescriptor[] checkMethod(@NotNull PsiMethod method, @NotNull InspectionManager manager, boolean isOnTheFly) {
    final Collection<PsiParameter> parameters = filterFinal(method.getParameterList().getParameters());
    final PsiCodeBlock body = method.getBody();
    if (body == null || parameters.isEmpty() || isOverrides(method)) {
      return ProblemDescriptor.EMPTY_ARRAY;
    }

    Collection<PsiParameter> writtenBeforeReadParameters = getWriteBeforeRead(parameters, body);
    if (writtenBeforeReadParameters.isEmpty() || MethodUtils.isOverridden(method)) {
      return ProblemDescriptor.EMPTY_ARRAY;
    }
    final List<ProblemDescriptor> result = new ArrayList<>();
    for (PsiParameter parameter : writtenBeforeReadParameters) {
      final PsiIdentifier identifier = parameter.getNameIdentifier();
      if (identifier != null && identifier.isPhysical()) {
        result.add(createProblem(manager, identifier, isOnTheFly));
      }
    }
    return result.toArray(ProblemDescriptor.EMPTY_ARRAY);
  }

  private static @NotNull ProblemDescriptor createProblem(@NotNull InspectionManager manager,
                                                          @NotNull PsiIdentifier identifier,
                                                          boolean isOnTheFly) {
    return manager.createProblemDescriptor(
      identifier,
      JavaBundle.message("inspection.parameter.can.be.local.problem.descriptor"),
      true,
      ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
      isOnTheFly,
      new ConvertParameterToLocalQuickFix()
    );
  }

  private static @NotNull List<PsiParameter> filterFinal(PsiParameter[] parameters) {
    final List<PsiParameter> result = new ArrayList<>(parameters.length);
    for (PsiParameter parameter : parameters) {
      if (!parameter.hasModifierProperty(PsiModifier.FINAL)) {
        result.add(parameter);
      }
    }
    return result;
  }

  private static Collection<PsiParameter> getWriteBeforeRead(@NotNull Collection<? extends PsiParameter> parameters,
                                                             @NotNull PsiCodeBlock body) {
    final ControlFlow controlFlow = getControlFlow(body);
    if (controlFlow == null) return Collections.emptyList();

    final Set<PsiParameter> result = filterParameters(controlFlow, parameters);
    if (result.isEmpty()) return Collections.emptyList();
    result.retainAll(ControlFlowUtil.getWrittenVariables(controlFlow, 0, controlFlow.getSize(), false));
    if (result.isEmpty()) return Collections.emptyList();
    for (final PsiReferenceExpression readBeforeWrite : ControlFlowUtil.getReadBeforeWrite(controlFlow)) {
      final PsiElement resolved = readBeforeWrite.resolve();
      if (resolved instanceof PsiParameter) {
        result.remove(resolved);
      }
    }

    return result;
  }

  private static Set<PsiParameter> filterParameters(@NotNull ControlFlow controlFlow, @NotNull Collection<? extends PsiParameter> parameters) {
    final Set<PsiVariable> usedVars = new HashSet<>(ControlFlowUtil.getUsedVariables(controlFlow, 0, controlFlow.getSize()));

    final Set<PsiParameter> result = new HashSet<>();
    for (PsiParameter parameter : parameters) {
      if (usedVars.contains(parameter)) {
        result.add(parameter);
      }
    }
    return result;
  }

  private static boolean isOverrides(PsiMethod method) {
    return SuperMethodsSearch.search(method, null, true, false).findFirst() != null;
  }

  private static @Nullable ControlFlow getControlFlow(final PsiElement context) {
    try {
      return ControlFlowFactory.getInstance(context.getProject())
        .getControlFlow(context, LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance());
    }
    catch (AnalysisCanceledException e) {
      return null;
    }
  }

  private static final class ConvertParameterToLocalQuickFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(ConvertParameterToLocalQuickFix.class);

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
      applyFix(project, previewDescriptor);
      return IntentionPreviewInfo.DIFF;
    }

    private static @NotNull List<PsiElement> moveDeclaration(@NotNull Project project, @NotNull PsiParameter variable) {
      final Collection<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(variable);
      if (references.isEmpty()) return Collections.emptyList();
      final PsiElement scope = variable.getDeclarationScope();
      if (!(scope instanceof PsiMethod method)) return Collections.emptyList();
      if (!IntentionPreviewUtils.prepareElementForWrite(method)) return Collections.emptyList();
      final PsiParameter[] parameters = method.getParameterList().getParameters();
      final List<ParameterInfoImpl> info = new ArrayList<>();
      for (int i = 0; i < parameters.length; i++) {
        PsiParameter psiParameter = parameters[i];
        if (psiParameter == variable) continue;
        info.add(ParameterInfoImpl.create(i).withName(psiParameter.getName()).withType(psiParameter.getType()));
      }
      final ParameterInfoImpl[] newParams = info.toArray(new ParameterInfoImpl[0]);
      final String visibilityModifier = VisibilityUtil.getVisibilityModifier(method.getModifierList());
      PsiElement moved = IntentionPreviewUtils.writeAndCompute(() -> copyVariableToMethodBody(variable, references));
      if (moved == null) return Collections.emptyList();
      SmartPsiElementPointer<PsiElement> newDeclaration = SmartPointerManager.createPointer(moved);
      if (IntentionPreviewUtils.isPreviewElement(variable)) {
        variable.delete();
      } else {
        var processor = JavaRefactoringFactory.getInstance(project).createChangeSignatureProcessor(
          method, false, visibilityModifier, method.getName(), method.getReturnType(), newParams,
          null, null, null, null
        );
        processor.run();
      }
      return Collections.singletonList(Objects.requireNonNull(newDeclaration.getElement()));
    }

    @Override
    public @NotNull String getFamilyName() {
      return JavaBundle.message("inspection.convert.to.local.quickfix");
    }

    @Override
    public boolean startInWriteAction() {
      return false;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiParameter variable = (PsiParameter)descriptor.getPsiElement().getParent();
      if (variable == null || !variable.isValid()) return; //weird. should not get here when field becomes invalid
      if (!IntentionPreviewUtils.prepareElementForWrite(descriptor.getPsiElement())) return;
      final PsiFile myFile = variable.getContainingFile();
      try {
        final List<PsiElement> newDeclarations = moveDeclaration(project, variable);
        if (newDeclarations.isEmpty()) return;
        positionCaretToDeclaration(project, myFile, newDeclarations.get(newDeclarations.size() - 1));
        newDeclarations.forEach(declaration -> inlineRedundant(declaration));
      }
      catch (IncorrectOperationException e) {
        LOG.error(e);
      }
    }

    private static @Nullable PsiElement copyVariableToMethodBody(PsiParameter variable, Collection<? extends PsiReferenceExpression> references) {
      final PsiCodeBlock anchorBlock = findAnchorBlock(references);
      if (anchorBlock == null) return null; // was assertion, but need to fix the case when obsolete inspection highlighting is left
      final PsiElement firstElement = getLowestOffsetElement(references);
      final String localName = variable.getName();
      if (firstElement == null) return null;
      final PsiElement anchor = getAnchorElement(anchorBlock, firstElement);
      if (anchor == null) return null;
      final PsiAssignmentExpression anchorAssignmentExpression = searchAssignmentExpression(anchor);
      final PsiExpression initializer;
      if (anchorAssignmentExpression != null && isVariableAssignment(anchorAssignmentExpression, variable)) {
        initializer = anchorAssignmentExpression.getRExpression();
      } else {
        initializer = variable.getInitializer();
      }
      final PsiElementFactory psiFactory = JavaPsiFacade.getElementFactory(variable.getProject());
      final PsiDeclarationStatement declaration = psiFactory.createVariableDeclarationStatement(localName, variable.getType(), initializer);
      if (ContainerUtil.exists(references, PsiUtil::isAccessedForWriting)) {
        PsiUtil.setModifierProperty((PsiLocalVariable)declaration.getDeclaredElements()[0], PsiModifier.FINAL, false);
      }
      final PsiElement newDeclaration;
      if (anchorAssignmentExpression != null && isVariableAssignment(anchorAssignmentExpression, variable)) {
        newDeclaration = new CommentTracker().replaceAndRestoreComments(anchor, declaration);
      } else if (anchorBlock.getParent() instanceof PsiSwitchStatement) {
        PsiElement parent = anchorBlock.getParent();
        PsiElement switchContainer = parent.getParent();
        newDeclaration = switchContainer.addBefore(declaration, parent);
      } else {
        newDeclaration =  anchorBlock.addBefore(declaration, anchor);
      }
      retargetReferences(psiFactory, localName, references);
      return newDeclaration;
    }

    private static void inlineRedundant(@Nullable PsiElement declaration) {
      if (declaration == null) return;
      final PsiLocalVariable newVariable = extractDeclared(declaration);
      if (newVariable != null) {
        final PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(newVariable.getInitializer());
        if (VariableAccessUtils.isLocalVariableCopy(newVariable, initializer)) {
          Collection<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(newVariable);
          IntentionPreviewUtils.write(() -> {
            for (PsiReferenceExpression reference : references) {
              CommonJavaInlineUtil.getInstance().inlineVariable(newVariable, initializer, reference, null);
            }
            declaration.delete();
          });
        }
      }
    }

    private static @Nullable PsiLocalVariable extractDeclared(@NotNull PsiElement declaration) {
      if (!(declaration instanceof PsiDeclarationStatement)) return null;
      final PsiElement[] declaredElements = ((PsiDeclarationStatement)declaration).getDeclaredElements();
      if (declaredElements.length != 1) return null;
      return ObjectUtils.tryCast(declaredElements[0], PsiLocalVariable.class);
    }

    private static void positionCaretToDeclaration(@NotNull Project project, @NotNull PsiFile psiFile, @NotNull PsiElement declaration) {
      if (!psiFile.isPhysical()) return;
      final Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
      if (editor != null && (IJSwingUtilities.hasFocus(editor.getComponent()) || ApplicationManager.getApplication().isUnitTestMode())) {
        final PsiFile openedFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (openedFile == psiFile) {
          editor.getCaretModel().moveToOffset(declaration.getTextOffset());
          editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
        }
      }
    }

    private static @Nullable PsiAssignmentExpression searchAssignmentExpression(@Nullable PsiElement anchor) {
      if (!(anchor instanceof PsiExpressionStatement)) return null;
      final PsiExpression anchorExpression = ((PsiExpressionStatement)anchor).getExpression();
      if (!(anchorExpression instanceof PsiAssignmentExpression)) return null;
      return (PsiAssignmentExpression)anchorExpression;
    }

    private static boolean isVariableAssignment(@NotNull PsiAssignmentExpression expression, @NotNull PsiVariable variable) {
      if (expression.getOperationTokenType() != JavaTokenType.EQ) return false;
      if (!(expression.getLExpression() instanceof PsiReferenceExpression leftExpression)) return false;
      return leftExpression.isReferenceTo(variable);
    }

    private static void retargetReferences(PsiElementFactory elementFactory, String localName, Collection<? extends PsiReference> refs)
      throws IncorrectOperationException {
      final PsiReferenceExpression refExpr = (PsiReferenceExpression)elementFactory.createExpressionFromText(localName, null);
      for (PsiReference ref : refs) {
        if (ref instanceof PsiReferenceExpression) {
          ((PsiReferenceExpression)ref).replace(refExpr);
        }
      }
    }

    private static @Nullable PsiElement getAnchorElement(PsiCodeBlock anchorBlock, @NotNull PsiElement firstElement) {
      PsiElement element = firstElement;
      while (element != null && element.getParent() != anchorBlock) {
        element = element.getParent();
      }
      return element;
    }

    private static @Nullable PsiElement getLowestOffsetElement(@NotNull Collection<? extends PsiReference> refs) {
      PsiElement firstElement = null;
      for (PsiReference reference : refs) {
        final PsiElement element = reference.getElement();
        if (!(element instanceof PsiReferenceExpression)) continue;
        if (firstElement == null || firstElement.getTextRange().getStartOffset() > element.getTextRange().getStartOffset()) {
          firstElement = element;
        }
      }
      return firstElement;
    }

    private static PsiCodeBlock findAnchorBlock(final Collection<? extends PsiReference> refs) {
      PsiCodeBlock result = null;
      for (PsiReference psiReference : refs) {
        final PsiElement element = psiReference.getElement();
        if (PsiUtil.isInsideJavadocComment(element)) continue;
        PsiCodeBlock block = PsiTreeUtil.getParentOfType(element, PsiCodeBlock.class);
        if (result == null || block == null) {
          result = block;
        }
        else {
          final PsiElement commonParent = PsiTreeUtil.findCommonParent(result, block);
          result = PsiTreeUtil.getParentOfType(commonParent, PsiCodeBlock.class, false);
          if (result == null) return null;
        }
      }
      return result;
    }
  }
}
