package bank.pbp.bff;

/**
 * A domain module present in this BFF build. Each domain's BFF module declares one as a Spring bean; GET /v1/modules
 * lists them, and the web app shows only those modules' tabs and Control Room panels.
 */
public record BffModule(String name) {
}
