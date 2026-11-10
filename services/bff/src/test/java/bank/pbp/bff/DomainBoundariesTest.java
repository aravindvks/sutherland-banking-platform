package bank.pbp.bff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * BFF routes per domain (phase A of the Next Version Roadmap): no domain's routes use another domain's, so any of
 * them can be left out of a bank's build. All may use the shared core: who is calling (security) and
 * Idempotency-Key handling (bank.pbp.bff.IdempotencyStore).
 */
@AnalyzeClasses(packages = "bank.pbp.bff", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundariesTest {

    private static final String ONBOARDING = "bank.pbp.bff.onboarding..";
    private static final String PAYMENTS = "bank.pbp.bff.payments..";
    private static final String CARDS = "bank.pbp.bff.cards..";
    private static final String CREDIT_CARDS = "bank.pbp.bff.creditcards..";
    private static final String COMPLIANCE = "bank.pbp.bff.compliance..";
    private static final String RECON = "bank.pbp.bff.recon..";
    private static final String[] DOMAINS = {ONBOARDING, PAYMENTS, CARDS, CREDIT_CARDS, COMPLIANCE, RECON};
    private static final String[] APP = {"bank.pbp.bff.me..", "bank.pbp.bff.push..", "bank.pbp.bff.staff.."};

    @ArchTest
    static final ArchRule core_knows_no_domain = noClasses().that().resideInAnyPackage("bank.pbp.bff", "bank.pbp.bff.security..")
            .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
            .because("login and idempotency are shared by every domain and must not name one");

    @ArchTest
    static final ArchRule onboarding_alone = alone(ONBOARDING, PAYMENTS, CARDS, CREDIT_CARDS, COMPLIANCE, RECON);

    @ArchTest
    static final ArchRule payments_alone = alone(PAYMENTS, ONBOARDING, CARDS, CREDIT_CARDS, COMPLIANCE, RECON);

    @ArchTest
    static final ArchRule cards_alone = alone(CARDS, ONBOARDING, PAYMENTS, CREDIT_CARDS, COMPLIANCE, RECON);

    @ArchTest
    static final ArchRule credit_cards_alone = alone(CREDIT_CARDS, ONBOARDING, PAYMENTS, CARDS, COMPLIANCE, RECON);

    @ArchTest
    static final ArchRule compliance_alone = alone(COMPLIANCE, ONBOARDING, PAYMENTS, CARDS, CREDIT_CARDS, RECON);

    @ArchTest
    static final ArchRule recon_alone = alone(RECON, ONBOARDING, PAYMENTS, CARDS, CREDIT_CARDS, COMPLIANCE);

    @ArchTest
    static final ArchRule domains_do_not_use_the_app = noClasses().that().resideInAnyPackage(DOMAINS)
            .should().dependOnClassesThat().resideInAnyPackage(APP);

    private static ArchRule alone(String domain, String... others) {
        return noClasses().that().resideInAPackage(domain).should().dependOnClassesThat().resideInAnyPackage(others);
    }
}
