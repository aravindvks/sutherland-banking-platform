package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Stage 1 step 4: identifiers travel in messages and JSON columns, and older messages are still read. */
class AccountIdentifierTest {

    @Test
    void originalShapeBecomesTheUkIdentifier() {
        AccountIdentifier id = AccountIdentifier.fromOriginalShape("040004", "12345678");
        assertThat(id.scheme()).isEqualTo("UK_SORT_CODE");
        assertThat(id.fields()).containsExactlyInAnyOrderEntriesOf(Map.of("sortCode", "040004", "accountNumber", "12345678"));
        assertThat(AccountIdentifier.fromOriginalShape(null, null)).isNull();
    }

    @Test
    void readsAndWritesTheJsonShape() {
        AccountIdentifier id = AccountIdentifier.fromOriginalShape("040004", "12345678");
        assertThat(AccountIdentifier.fromJson(id.toJson())).isEqualTo(id);
    }

    @Test
    void absentOrIncompleteJsonIsNull() {
        assertThat(AccountIdentifier.fromJson(null)).isNull();
        assertThat(AccountIdentifier.fromJson(Map.of("scheme", "UK_SORT_CODE"))).isNull();
        assertThat(AccountIdentifier.fromJson("UK_SORT_CODE")).isNull();
    }

    @Test
    void nullFieldValuesAreLeftOut() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("sortCode", "040004");
        fields.put("accountNumber", null);
        assertThat(AccountIdentifier.fromJson(Map.of("scheme", "UK_SORT_CODE", "fields", fields)).fields())
                .containsOnlyKeys("sortCode");
    }
}
