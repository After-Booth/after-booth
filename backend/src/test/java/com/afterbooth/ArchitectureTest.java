package com.afterbooth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Backend design rules from docs/CONVENTIONS.md. Change both together. */
class ArchitectureTest {

  private static final List<String> DOMAINS =
      List.of("inventory", "intake", "request", "allocation", "handover", "stats");

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.afterbooth");

  @Test
  void controllers_do_not_touch_repositories_or_entities() {
    noClasses()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..repository..")
        .orShould()
        .dependOnClassesThat()
        .areAnnotatedWith(Entity.class)
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void domains_do_not_touch_other_domains_repositories_or_controllers() {
    for (String domain : DOMAINS) {
      String own = "com.afterbooth." + domain + "..";
      noClasses()
          .that()
          .resideOutsideOfPackage(own)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.afterbooth." + domain + ".repository..",
              "com.afterbooth." + domain + ".controller..")
          .allowEmptyShould(true)
          .check(CLASSES);
    }
  }

  @Test
  void domain_packages_have_no_cycles() {
    slices().matching("com.afterbooth.(*)..").should().beFreeOfCycles().check(CLASSES);
  }

  @Test
  void no_field_injection() {
    NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(CLASSES);
  }

  @Test
  void entities_have_no_public_setters() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(Entity.class)
        .and()
        .haveNameStartingWith("set")
        .should()
        .notBePublic()
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void only_ai_and_mail_packages_use_an_http_client() {
    noClasses()
        .that()
        .resideOutsideOfPackages("com.afterbooth.ai..", "com.afterbooth.member.mail..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web.client..",
            "org.springframework.http.client..",
            "java.net.http..")
        .check(CLASSES);
  }

  @Test
  void time_comes_from_injected_clock() {
    noClasses()
        .should()
        .callMethod(LocalDateTime.class, "now")
        .orShould()
        .callMethod(LocalDate.class, "now")
        .orShould()
        .callMethod(ZonedDateTime.class, "now")
        .orShould()
        .callMethod(Instant.class, "now")
        .check(CLASSES);
  }
}
