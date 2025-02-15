package de.plushnikov.intellij.plugin.processor.clazz.log;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

abstract class AbstractSimpleLogProcessor extends AbstractLogProcessor {
  private final @NotNull String loggerType;
  private final @NotNull String loggerInitializer;

  AbstractSimpleLogProcessor(
    @NotNull String supportedAnnotationClass,
    @NotNull String loggerType,
    @NotNull String loggerInitializer
  ) {
    super(supportedAnnotationClass);
    this.loggerType = loggerType;
    this.loggerInitializer = loggerInitializer;
  }

  @Override
  public final @NotNull String getLoggerType(@NotNull PsiClass psiClass) {
    return loggerType;
  }

  @Override
  final @NotNull String getLoggerInitializer(@NotNull PsiClass psiClass) {
    return loggerInitializer;
  }
}

abstract class AbstractTopicSupportingSimpleLogProcessor extends AbstractSimpleLogProcessor {
  private final @NotNull LoggerInitializerParameter defaultParameter;

  AbstractTopicSupportingSimpleLogProcessor(
    @NotNull String supportedAnnotationClass,
    @NotNull String loggerType,
    @NotNull String loggerInitializer,
    @NotNull LoggerInitializerParameter defaultParameter
  ) {
    super(supportedAnnotationClass, loggerType, loggerInitializer);
    this.defaultParameter = defaultParameter;
  }

  @Override
  final @NotNull List<LoggerInitializerParameter> getLoggerInitializerParameters(@NotNull PsiClass psiClass, boolean topicPresent) {
    return Collections.singletonList(topicPresent ? LoggerInitializerParameter.TOPIC : defaultParameter);
  }
}
