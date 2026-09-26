package br.com.ciclo.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.*;

@AnalyzeClasses(
    packages = "br.com.ciclo.identity",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final com.tngtech.archunit.lang.ArchRule domain_is_pure =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "..application..",
              "..infrastructure..",
              "..presentation..");

  @ArchTest
  static final com.tngtech.archunit.lang.ArchRule application_is_inner =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..infrastructure..",
              "..presentation..",
              "org.springframework..",
              "jakarta.persistence..");
}
