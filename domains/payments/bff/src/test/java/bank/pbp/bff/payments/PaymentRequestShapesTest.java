package bank.pbp.bff.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.MarketCurrencyValidator;
import bank.pbp.common.market.MarketPack;
import bank.pbp.market.uk.UkMarketPack;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Market packs, Stage 1 step 3: a payee account arrives in either shape. The original sortCode/accountNumber
 * request behaves exactly as before (same messages, same command, same idempotency hash); the new {@code account}
 * shape is checked by the market pack and gives the same command.
 */
class PaymentRequestShapesTest {

    private static final MarketPack UK = new UkMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0), LocalTime.of(17, 30), Set.of(),
            new BigDecimal("5000.00"));

    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider().configure()
            .constraintValidatorFactory(new ConstraintValidatorFactory() {
                private final ConstraintValidatorFactory fallback = Validation.byDefaultProvider().configure()
                        .getDefaultConstraintValidatorFactory();

                @Override
                @SuppressWarnings("unchecked")
                public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                    if (key == ValidAccountValidator.class) {
                        return (T) new ValidAccountValidator(UK);
                    }
                    if (key == MarketCurrencyValidator.class) {
                        return (T) new MarketCurrencyValidator(UK);
                    }
                    if (key == bank.pbp.common.market.MarketSchemeValidator.class) {
                        return (T) new bank.pbp.common.market.MarketSchemeValidator(UK);
                    }
                    return fallback.getInstance(key);
                }

                @Override
                public void releaseInstance(ConstraintValidator<?, ?> instance) {
                }
            }).buildValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static PaymentRequest pay(PaymentRequest.Creditor creditor) {
        return new PaymentRequest("acc-1", creditor, new PaymentRequest.Money("12.50", "GBP"), null, "Rent", null, "FPS");
    }

    private static Map<String, String> problems(Object request) {
        Map<String, String> out = new TreeMap<>();
        for (ConstraintViolation<Object> v : VALIDATOR.validate(request)) {
            out.merge(v.getPropertyPath().toString(), v.getMessage(), (a, b) -> a + "; " + b);
        }
        return out;
    }

    private static PaymentRequest.Creditor legacy(String sortCode, String accountNumber) {
        return new PaymentRequest.Creditor("Jane Doe", sortCode, accountNumber, null);
    }

    private static PaymentRequest.Creditor account(String scheme, Map<String, String> fields) {
        return new PaymentRequest.Creditor("Jane Doe", null, null, new AccountBody(scheme, fields));
    }

    @Test
    void originalShapeIsAcceptedAsBefore() {
        assertThat(problems(pay(legacy("040004", "12345678")))).isEmpty();
    }

    @Test
    void originalShapeErrorsStayOnTheSameFieldsWithTheSameMessages() {
        assertThat(problems(pay(legacy("04-00-04", "1234567"))))
                .containsEntry("creditor.sortCode", "must be 6 digits")
                .containsEntry("creditor.accountNumber", "must be 8 digits");
        assertThat(problems(pay(legacy(null, " "))))
                .containsEntry("creditor.sortCode", "must not be blank")
                .containsEntry("creditor.accountNumber", "must not be blank");
    }

    @Test
    void newShapeIsCheckedByTheMarketPack() {
        assertThat(problems(pay(account("UK_SORT_CODE", Map.of("sortCode", "040004", "accountNumber", "12345678")))))
                .isEmpty();
        assertThat(problems(pay(account("UK_SORT_CODE", Map.of("sortCode", "4000", "accountNumber", "12345678")))))
                .containsOnly(Map.entry("creditor.account.sortCode", "must be 6 digits"));
        assertThat(problems(pay(account("IBAN", Map.of("iban", "AE070331234567890123456")))))
                .containsOnlyKeys("creditor.account.scheme");
    }

    @Test
    void bothShapesAtOnceAreRefused() {
        PaymentRequest.Creditor both = new PaymentRequest.Creditor("Jane Doe", "040004", "12345678",
                new AccountBody("UK_SORT_CODE", Map.of("sortCode", "040004", "accountNumber", "12345678")));
        assertThat(problems(pay(both))).containsOnlyKeys("creditor.account");
    }

    @Test
    void bothShapesGiveTheSameCommand() {
        Map<String, Object> before = Map.of("name", "Jane Doe", "sortCode", "040004", "accountNumber", "12345678",
                "account", Map.of("scheme", "UK_SORT_CODE",
                        "fields", Map.of("sortCode", "040004", "accountNumber", "12345678")));
        assertThat(PaymentService.creditorData(legacy("040004", "12345678"))).isEqualTo(before);
        assertThat(PaymentService.creditorData(account("UK_SORT_CODE",
                Map.of("sortCode", "040004", "accountNumber", "12345678")))).isEqualTo(before);
    }

    @Test
    void originalShapeSerialisesAsBeforeSoIdempotencyHashesDoNotChange() throws Exception {
        assertThat(new ObjectMapper().writeValueAsString(legacy("040004", "12345678")))
                .isEqualTo("{\"name\":\"Jane Doe\",\"sortCode\":\"040004\",\"accountNumber\":\"12345678\"}");
    }

    @Test
    void payeeDetailsTakeEitherShapeToo() {
        assertThat(problems(new PayeeController.PayeeDetails("Jane Doe", "040004", "12345678", null, null))).isEmpty();
        assertThat(problems(new PayeeController.PayeeDetails("Jane Doe", null, null, null,
                new AccountBody("UK_SORT_CODE", Map.of("sortCode", "040004", "accountNumber", "1")))))
                .containsOnly(Map.entry("account.accountNumber", "must be 8 digits"));
    }
}
