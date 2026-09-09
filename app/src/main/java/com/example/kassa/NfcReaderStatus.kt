package com.example.kassa

enum class NfcReaderStatus {
    DISCONNECTED,
    CONNECTING,
    NO_READER,
    CONNECTED,
    WAITING_FOR_CARD,
    CARD_PRESENT,
    ERROR
}
