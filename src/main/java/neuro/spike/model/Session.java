package neuro.spike.model;

/** Top-level recording session. Establishes the shared time origin/basis. */
public record Session(String projectId,
                      String name,
                      String timeBasis,
                      Quantity duration,
                      String sourceRef) {
    public Session {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId required");
        if (timeBasis == null || timeBasis.isBlank()) throw new IllegalArgumentException("timeBasis required");
    }
}
