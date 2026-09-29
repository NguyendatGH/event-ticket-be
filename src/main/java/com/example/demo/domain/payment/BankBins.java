package com.example.demo.domain.payment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BIN Napas của ngân hàng VN, dùng làm đích lệnh chi. Phải kiểm vì {@code counterAccountBankId} trong webhook PayOS
 * có khi là mã CITAD 8 số hoặc rỗng (khách trả bằng ví) — API chi hộ từ chối cả hai, mà đoán BIN là có ngày
 * chuyển tiền cho người lạ. Sai dạng thì coi như KHÔNG BIẾT ngân hàng, bắt khách tự chọn.
 * Thứ tự trong map = thứ tự dropdown.
 */
public final class BankBins {

    /** Một ngân hàng cho FE dựng dropdown chọn ngân hàng. */
    public record Bank(String bin, String name) {}

    private static final Map<String, String> NAMES = names();
    private static final List<Bank> ALL = NAMES.entrySet().stream().map(e -> new Bank(e.getKey(), e.getValue())).toList();

    private BankBins() {
    }

    /** BIN Napas là đúng 6 chữ số. Mã CITAD 8 số, mã rỗng, chữ cái đều trượt. */
    public static boolean isBin(String value) {
        return value != null && value.trim().matches("\\d{6}");
    }

    /** Tên ngân hàng để hiển thị; BIN lạ thì trả về chính nó. null/rỗng trả null. */
    public static String nameOf(String bin) {
        if (bin == null || bin.isBlank()) return null;
        return NAMES.getOrDefault(bin.trim(), bin.trim());
    }

    /** Danh sách cho FE, phổ biến trước. */
    public static List<Bank> all() {
        return ALL;
    }

    private static Map<String, String> names() {
        Map<String, String> m = new LinkedHashMap<>();
        // phổ biến nhất, lên đầu dropdown
        m.put("970422", "MB Bank");
        m.put("970436", "Vietcombank");
        m.put("970415", "VietinBank");
        m.put("970418", "BIDV");
        m.put("970407", "Techcombank");
        m.put("970416", "ACB");
        m.put("970432", "VPBank");
        m.put("970423", "TPBank");
        m.put("970405", "Agribank");
        m.put("970403", "Sacombank");
        // còn lại, theo BIN
        m.put("970400", "Saigonbank");
        m.put("970406", "DongA Bank");
        m.put("970408", "GPBank");
        m.put("970409", "Bac A Bank");
        m.put("970410", "Standard Chartered");
        m.put("970412", "PVcomBank");
        m.put("970414", "MBV (Oceanbank)");
        m.put("970419", "NCB");
        m.put("970421", "VRB");
        m.put("970424", "Shinhan Bank");
        m.put("970425", "ABBANK");
        m.put("970426", "MSB");
        m.put("970427", "VietABank");
        m.put("970428", "Nam A Bank");
        m.put("970429", "SCB");
        m.put("970430", "PGBank");
        m.put("970431", "Eximbank");
        m.put("970433", "VietBank");
        m.put("970434", "Indovina Bank");
        m.put("970437", "HDBank");
        m.put("970438", "BaoViet Bank");
        m.put("970439", "Public Bank");
        m.put("970440", "SeABank");
        m.put("970441", "VIB");
        m.put("970442", "Hong Leong Bank");
        m.put("970443", "SHB");
        m.put("970444", "CBBank");
        m.put("970446", "Co-opBank");
        m.put("970448", "OCB");
        m.put("970449", "LPBank");
        m.put("970452", "KienlongBank");
        m.put("970454", "BVBank");
        m.put("970455", "IBK");
        m.put("970457", "Woori Bank");
        m.put("970458", "UOB");
        m.put("970462", "Kookmin Bank HN");
        m.put("970463", "Kookmin Bank HCM");
        m.put("546034", "CAKE");
        m.put("546035", "Ubank");
        m.put("963388", "Timo");
        m.put("971005", "Viettel Money");
        m.put("971011", "VNPT Money");
        return Collections.unmodifiableMap(m);
    }
}
