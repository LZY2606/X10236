package neuro.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A unit carries a stable identity that survives renumbering, merge and split. The cluster number
 * ({@code label}) is only a display attribute and must never be used as identity.
 */
public final class Unit {
    private final String id;
    private String label;
    private final long bornSeq;
    private final List<String> lineageParents = new ArrayList<>();
    private final List<Membership> members = new ArrayList<>();

    public Unit(String id, String label, long bornSeq) {
        this.id = id;
        this.label = label;
        this.bornSeq = bornSeq;
    }

    public String id() { return id; }
    public String label() { return label; }
    public long bornSeq() { return bornSeq; }
    public List<String> lineageParents() { return List.copyOf(lineageParents); }
    public List<Membership> members() { return List.copyOf(members); }

    public void setLabel(String label) { this.label = label; }
    public void addParent(String p) { if (!lineageParents.contains(p)) lineageParents.add(p); }

    public void addConfirmed(String spikeId) {
        members.removeIf(m -> m.spikeId().equals(spikeId));
        members.add(new Membership(spikeId, Membership.MemberStatus.CONFIRMED));
    }

    public void addShared(String spikeId) {
        members.removeIf(m -> m.spikeId().equals(spikeId));
        members.add(new Membership(spikeId, Membership.MemberStatus.SHARED));
    }

    public void removeMember(String spikeId) {
        members.removeIf(m -> m.spikeId().equals(spikeId));
    }

    public boolean hasSpike(String spikeId) {
        return members.stream().anyMatch(m -> m.spikeId().equals(spikeId));
    }

    public Set<String> spikeIdSet() {
        Set<String> s = new LinkedHashSet<>();
        for (Membership m : members) s.add(m.spikeId());
        return s;
    }
}
