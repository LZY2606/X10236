package spike.core;

/** A decision branch. {@code main} is created at session init; review actions fork branches. */
public record Branch(
        String id,
        String name,
        String parentBranchId,
        Long parentDecisionSeq,
        long createdAtSeq,
        String note
) {
    public static final String MAIN = "main";
}
