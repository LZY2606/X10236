package spike.model;

/** A probe site. Coordinates always carry their basis and unit. */
public record ProbeChannel(String id, int index, Quantity x, Quantity y, String coordBasis) {
}
