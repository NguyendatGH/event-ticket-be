package com.example.demo.domain.payment;

import java.nio.charset.StandardCharsets;

/**
 * Builds an EMVCo / Napas VietQR payload (QR IBFT to account, "QRIBFTTA"). Used for the mode B refund QR the
 * merchant scans and for the stub collection client's fake payment QR.
 *
 * <pre>
 * 00 02 01                      payload format indicator
 * 01 02 12                      point of initiation: dynamic
 * 38 LL  [00 06 A000000727]     Napas GUID
 *        [01 LL [00 06 bin][01 LL account]]
 *        [02 08 QRIBFTTA]
 * 53 03 704                     currency VND
 * 54 LL amount                  (omitted when amount <= 0)
 * 58 02 VN
 * 62 LL [08 LL content]         additional data: purpose of transaction (omitted when blank)
 * 63 04 CRC                     CRC16-CCITT-FALSE over everything including "6304", uppercase hex
 * </pre>
 */
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

  /** ASCII letters, digits and spaces only, collapsed and trimmed, at most {@link #MAX_CONTENT} chars. */
  public static String sanitizeContent(String content) {
    if (content == null) return "";
    String s = content.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    return s.length() > MAX_CONTENT ? s.substring(0, MAX_CONTENT).trim() : s;
  }

  static String tlv(String id, String value) {
    if (value.length() > 99) throw new IllegalArgumentException("TLV value too long for id " + id);
    return id + String.format("%02d", value.length()) + value;
  }

  /** CRC16-CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final xor. Uppercase 4-hex. */
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
