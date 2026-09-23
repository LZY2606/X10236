package spike.model;

import java.util.List;

public record ProbeGeometry(String id, String name, String coordBasis, List<ProbeChannel> channels) {
}
