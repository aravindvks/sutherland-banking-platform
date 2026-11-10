package bank.pbp.orchestrator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Which domain may use which (phase A of the Next Version Roadmap). Gradle already stops a domain module from
 * compiling against a module it does not declare; these rules also cover the shared core, the app itself and the
 * packages inside one module, and fail with a readable message when someone adds a dependency.
 *
 * <p>Allowed: onboarding → compliance (a rescreening hit starts the freeze saga); compliance → payments (a staff
 * decision on a held payment signals its saga). Everything may use the core (bank.pbp.orchestrator.temporal).
 */
@AnalyzeClasses(packages = "bank.pbp.orchestrator", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundariesTest {

    private static final String ONBOARDING = "bank.pbp.orchestrator.onboarding..";
    private static final String COMPLIANCE = "bank.pbp.orchestrator.compliance..";
    private static final String PAYMENTS = "bank.pbp.orchestrator.payments..";
    private static final String SCHEDULES = "bank.pbp.orchestrator.schedules..";
    private static final String DIRECT_DEBITS = "bank.pbp.orchestrator.directdebits..";
    private static final String CARDS = "bank.pbp.orchestrator.cards..";
    private static final String CREDIT_CARDS = "bank.pbp.orchestrator.creditcards..";
    private static final String CORE = "bank.pbp.orchestrator.temporal..";
    private static final String OBSERVABILITY = "bank.pbp.orchestrator.observability..";

    @ArchTest
    static final ArchRule core_knows_no_domain = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, COMPLIANCE, PAYMENTS, SCHEDULES,
                    DIRECT_DEBITS, CARDS, CREDIT_CARDS)
            .because("the orchestrator starts whatever domains register workers; the core must not name them");

    @ArchTest
    static final ArchRule debit_cards_stand_alone = noClasses().that().resideInAPackage(CARDS)
            .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, COMPLIANCE, PAYMENTS, SCHEDULES,
                    DIRECT_DEBITS, CREDIT_CARDS);

    @ArchTest
    static final ArchRule credit_cards_stand_alone = noClasses().that().resideInAPackage(CREDIT_CARDS)
            .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, COMPLIANCE, PAYMENTS, SCHEDULES,
                    DIRECT_DEBITS, CARDS)
            .because("credit cards must be switchable off without touching other domains");

    @ArchTest
    static final ArchRule payments_use_no_other_domain = noClasses().that()
            .resideInAnyPackage(PAYMENTS, SCHEDULES, DIRECT_DEBITS)
            .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, COMPLIANCE, CARDS, CREDIT_CARDS);

    @ArchTest
    static final ArchRule compliance_uses_only_payments = noClasses().that().resideInAPackage(COMPLIANCE)
            .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, SCHEDULES, DIRECT_DEBITS, CARDS,
                    CREDIT_CARDS);

    @ArchTest
    static final ArchRule onboarding_uses_only_compliance = noClasses().that().resideInAPackage(ONBOARDING)
            .should().dependOnClassesThat().resideInAnyPackage(PAYMENTS, SCHEDULES, DIRECT_DEBITS, CARDS,
                    CREDIT_CARDS);

    @ArchTest
    static final ArchRule domains_do_not_use_the_app = noClasses().that()
            .resideInAnyPackage(ONBOARDING, COMPLIANCE, PAYMENTS, SCHEDULES, DIRECT_DEBITS, CARDS, CREDIT_CARDS, CORE)
            .should().dependOnClassesThat().resideInAPackage(OBSERVABILITY)
            .orShould().dependOnClassesThat().haveSimpleName("OrchestratorApplication");
}
