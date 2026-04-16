package cn.suhoan.anaxa.storage;

import java.util.Map;

interface SegmentWritableEntry {
    String id();

    long sequence();

    boolean tombstone();

    float norm();

    byte[] vectorBytes();

    Map<String, Object> payload();
}
