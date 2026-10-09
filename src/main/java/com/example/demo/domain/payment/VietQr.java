package com.example.demo.domain.payment;

import java.nio.charset.StandardCharsets;

public final class VietQr {
  public static final String NAPAS_GUID = "A000000727";
  public static final String SERVICE_TO_ACCOUNT = "QRIBFTTA";
  public static final int MAX_CONTENT = 25;

  private VietQr() {}

  public static String build(String bin, String accountNo, long amount, String content) {
    String cleanBin = digits(bin);
    String cleanAcc = accountNo == null ? "" : accountNo.trim();
    if (cleanBin.isEmpty() || cleanAcc.isEmpty()) {
      throw new IllegalArgumentException("VietQR needs a bank BIN and an account number");
    }
    StringBuilder sb = new StringBuilder(160);
    sb.append(tlv("00", "01"));
    sb.append(tlv("01", "12"));
    String beneficiary = tlv("00", cleanBin) + tlv("01", cleanAcc);
    String merchantInfo = tlv("00", NAPAS_GUID) + tlv("01", beneficiary) + tlv("02", SERVICE_TO_ACCOUNT);
    sb.append(tlv("38", merchantInfo));
    sb.append(tlv("53", "704"));
    if (amount > 0) sb.append(tlv("54", Long.toString(amount)));
    sb.append(tlv("58", "VN"));
    String purpose = sanitizeContent(content);
    if (!purpose.isEmpty()) sb.append(tlv("62", tlv("08", purpose)));
    sb.append("6304");
    sb.append(crc16(sb.toString()));
    return sb.toString();
  }

  public static String sanitizeContent(String content) {
    if (content == null) return "";
    String s = content.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    return s.length() > MAX_CONTENT ? s.substring(0, MAX_CONTENT).trim() : s;
  }

  static String tlv(String id, String value) {
    if (value.length() > 99) throw new IllegalArgumentException("TLV value too long for id " + id);
    return id + String.format("%02d", value.length()) + value;
  }

  public static String crc16(String s) {
    int crc = 0xFFFF;
    for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
      crc ^= (b & 0xFF) << 8;
      for (int i = 0; i < 8; i++) {
        crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
        crc &= 0xFFFF;
      }
    }
    return String.format("%04X", crc);
  }

  private static String digits(String s) {
    return s == null ? "" : s.replaceAll("\\D", "");
  }
}
