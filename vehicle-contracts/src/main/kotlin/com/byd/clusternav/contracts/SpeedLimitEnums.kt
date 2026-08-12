package com.byd.clusternav.contracts

/** Semantic sign shape; numeric vehicle encodings remain profile-specific and unproven. */
enum class SpeedSignType {
    REGULATORY,
    ADVISORY,
    VARIABLE,
    UNKNOWN,
}

/** Semantic applicability; numeric vehicle encodings remain outside this neutral contract. */
enum class SpeedLimitType {
    ABSOLUTE,
    CONDITIONAL,
    TEMPORARY,
    UNKNOWN,
}

enum class SpeedUnit {
    KPH,
    MPH,
}

enum class SpeedLimitSource {
    WAZE,
    VIETMAP,
    NONE,
}

enum class FreshnessState {
    FRESH,
    STALE,
    UNAVAILABLE,
}

enum class SpeedLimitClearReason {
    ZERO_VALUE,
    TTL_EXPIRED,
    SOURCE_SWITCHED,
    PROVIDER_DISCONNECTED,
    SOURCE_STOPPED,
    MASTER_DISABLED,
    OUTPUT_DISABLED,
    PROCESS_RESTARTED,
    QUEUE_SATURATED,
}
