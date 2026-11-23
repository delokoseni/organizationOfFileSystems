package io.github.delokoseni.model;

public enum PartitionStyle {
    GPT,
    MBR,
    HYBRID,
    AMBIGUOUS,
    RAW,
    UNKNOWN;

    public String displayName() {
        return switch (this) {
            case GPT -> "GPT";
            case MBR -> "MBR";
            case HYBRID -> "Гибридная MBR/GPT";
            case AMBIGUOUS -> "Неоднозначная";
            case RAW -> "RAW";
            case UNKNOWN -> "Не определён";
        };
    }
}
