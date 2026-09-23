package gsb;

public final class Model {
    private Model() {}

    public static class GsbException extends RuntimeException {
        public final String status;

        public GsbException(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class BudgetExhausted extends GsbException {
        public BudgetExhausted(String message) {
            super("budget_exhausted", message);
        }
    }

    public static final class FormatCorrupt extends GsbException {
        public final String position;

        public FormatCorrupt(String position, String message) {
            super("format_corrupt", position + ": " + message);
            this.position = position;
        }
    }

    public static final class Unsolvable extends GsbException {
        public Unsolvable(String message) {
            super("unsolvable", message);
        }
    }
}
