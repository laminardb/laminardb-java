package io.laminardb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.laminardb.LaminarConnectionException;
import io.laminardb.LaminarException;
import io.laminardb.LaminarIngestionException;
import io.laminardb.LaminarQueryException;
import io.laminardb.LaminarSchemaException;
import io.laminardb.LaminarShutdownException;
import io.laminardb.LaminarSubscriptionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The bridge's error mapping mirrors src/error.rs (plan 02 §5). */
class CallbackBridgeErrorsTest {

    @ParameterizedTest
    @CsvSource({
        "100, CONNECTION",
        "101, CONNECTION",
        "200, SCHEMA",
        "300, INGESTION",
        "400, QUERY",
        "401, QUERY",
        "500, SUBSCRIPTION",
        "900, INTERNAL",
        "901, SHUTDOWN",
        "999, BASE"
    })
    void mapsCodesToTheirDocumentedClasses(int code, String category) {
        LaminarException error = CallbackBridge.LaminarErrors.forCode("msg", code);
        Class<?> expected =
                switch (category) {
                    case "CONNECTION" -> LaminarConnectionException.class;
                    case "SCHEMA" -> LaminarSchemaException.class;
                    case "INGESTION" -> LaminarIngestionException.class;
                    case "QUERY" -> LaminarQueryException.class;
                    case "SUBSCRIPTION" -> LaminarSubscriptionException.class;
                    case "INTERNAL" -> io.laminardb.LaminarInternalException.class;
                    case "SHUTDOWN" -> LaminarShutdownException.class;
                    default -> LaminarException.class;
                };
        assertThat(error).isInstanceOf(expected);
        assertThat(error.getCode()).isEqualTo(code);
        assertThat(error.getMessage()).isEqualTo("msg");
    }
}
