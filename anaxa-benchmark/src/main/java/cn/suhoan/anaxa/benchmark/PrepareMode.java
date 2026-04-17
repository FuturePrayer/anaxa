package cn.suhoan.anaxa.benchmark;

public enum PrepareMode {
    NONE("none"),
    FLUSH("flush"),
    FLUSH_AND_COMPACT("flush,compact");

    private final String label;

    PrepareMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
