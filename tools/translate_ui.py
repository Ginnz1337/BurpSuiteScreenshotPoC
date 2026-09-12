"""One-shot: replaces the Vietnamese UI strings with English (already applied).

Exact-match pairs, and the script fails loudly on a pair that does not match, so a stale
entry cannot pass silently. Comments are left alone; only text a user reads is translated.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src" / "main" / "java" / "burp" / "screenshot"

EDITS = {
    "BurpExtender.java": [
        ('"Không mở được studio: "', '"Could not open the studio: "'),
        ('"Đã copy ảnh PoC vào clipboard (2x)"', '"PoC image copied to the clipboard (2x)"'),
        ('"Không copy được ảnh: "', '"Could not copy the image: "'),
    ],
    "ui/InspectorPanel.java": [
        ('CONFIG("Cấu hình")', 'CONFIG("Settings")'),
        ('new AccordionSection("Template", "Bộ cấu hình đã lưu", true)',
         'new AccordionSection("Template", "Saved presets", true)'),
        ('Fields.row("Đang dùng", templateCombo)', 'Fields.row("Current", templateCombo)'),
        ('Buttons.secondary("Tải lại", Icons.refresh(null, 13))',
         'Buttons.secondary("Reload", Icons.refresh(null, 13))'),
        ('"Đọc lại danh sách template từ đĩa"', '"Read the template list from disk again"'),
        ('Buttons.primary("Lưu", Icons.save(null, 13))',
         'Buttons.primary("Save", Icons.save(null, 13))'),
        ('"Ghi thay đổi vào template hiện tại"',
         '"Write the changes into the current template"'),
        ('Buttons.secondary("Lưu thành mới", Icons.plus(null, 13))',
         'Buttons.secondary("Save as new", Icons.plus(null, 13))'),
        ('new AccordionSection("Header ẩn", "Mỗi dòng một tên header", true)',
         'new AccordionSection("Hidden headers", "One header name per line", true)'),
        ('t -> t == ScopeTarget.BOTH ? "Cả hai" : t.getDisplayName(),',
         'ScopeTarget::getDisplayName,'),
        ('Fields.row("Áp dụng cho", headerScope)', 'Fields.row("Applies to", headerScope)'),
        ('"Thêm dấu ! trước tên để luôn hiện header đó"',
         '"Prefix a name with ! to always show that header"'),
        ('new AccordionSection("Bố cục", null, true)',
         'new AccordionSection("Layout", null, true)'),
        ('m -> m == LayoutMode.SIDE_BY_SIDE ? "Song song" : "Xếp dọc",',
         'LayoutMode::getDisplayName,'),
        ('v -> "Mac".equals(v) ? "Mac 3 chấm" : "Caido sạch",',
         'v -> "Mac".equals(v) ? "Mac dots" : "Caido flat",'),
        ('Fields.row("Kiểu thanh URL", windowStyle)', 'Fields.row("URL bar style", windowStyle)'),
        ('Fields.row("Bề rộng", widthCombo)', 'Fields.row("Width", widthCombo)'),
        ('Fields.row("Độ phân giải khi lưu / copy", scaleCombo)',
         'Fields.row("Resolution on save / copy", scaleCombo)'),
        ('new AccordionSection("Kiểu card", null, false)',
         'new AccordionSection("Card style", null, false)'),
        ('labelled("Bo góc", roundedToggle)', 'labelled("Rounded corners", roundedToggle)'),
        ('labelled("Đổ bóng", shadowToggle)', 'labelled("Drop shadow", shadowToggle)'),
        ('new AccordionSection("Hiển thị", null, false)',
         'new AccordionSection("Display", null, false)'),
        ('labelled("Thời gian", timestampToggle)', 'labelled("Timestamp", timestampToggle)'),
        ('labelled("Kích thước và thời lượng", responseInfoToggle)',
         'labelled("Size and duration", responseInfoToggle)'),
        ('labelled("Ngắt dòng dài", wrapToggle)', 'labelled("Wrap long lines", wrapToggle)'),
        ('new AccordionSection("Cắt dòng", "Ví dụ 1-6, 15-30", false)',
         'new AccordionSection("Line ranges", "For example 1-6, 15-30", false)'),
        ('"Để trống để hiện tất cả. Khoảng trống hiện thành ···"',
         '"Leave empty to show everything. Gaps appear as ···"'),
        ('Fields.row("Dòng request", requestRangesField)',
         'Fields.row("Request lines", requestRangesField)'),
        ('Fields.row("Dòng response", responseRangesField)',
         'Fields.row("Response lines", responseRangesField)'),
        ('new AccordionSection("Màu cú pháp", "Chỉnh màu từng thành phần", false)',
         'new AccordionSection("Syntax colors", "Tune the color of each part", false)'),
        ('ruleGroupHeader("Highlights", "Tô nền để làm nổi bật", this::addHighlight, true)',
         'ruleGroupHeader("Highlights", "Mark the things that matter", this::addHighlight, true)'),
        ('ruleGroupHeader("Redactions", "Che thông tin nhạy cảm", this::addRedaction, false)',
         'ruleGroupHeader("Redactions", "Mask sensitive values", this::addRedaction, false)'),
        ('Buttons.secondary("Mẫu sẵn", Icons.chevronDown(null, 12))',
         'Buttons.secondary("Presets", Icons.chevronDown(null, 12))'),
        ('Buttons.secondary("Thêm", Icons.plus(null, 13))',
         'Buttons.secondary("Add", Icons.plus(null, 13))'),
        ('Fields.muted("Chưa có rule nào.")', 'Fields.muted("No rules yet.")'),
        ('"Lưu thành template mới", "Tên template mới:"',
         '"Save as a new template", "New template name:"'),
    ],
    "ui/RuleCard.java": [
        ('Fields.muted("Phạm vi")', 'Fields.muted("Scope")'),
        ('"Màu highlight"', '"Highlight color"'),
        ('Fields.muted("Màu")', 'Fields.muted("Color")'),
        ('m -> m == RedactionMode.BLUR ? "Làm mờ" : "Che đen"',
         'm -> m == RedactionMode.BLUR ? "Blur" : "Solid"'),
        ('"0 = cả chuỗi khớp, 1..9 = nhóm bắt trong regex"',
         '"0 = the whole match, 1..9 = a regex capture group"'),
        ('Fields.muted("Kiểu che")', 'Fields.muted("Style")'),
        ('Fields.muted("Nhóm")', 'Fields.muted("Group")'),
        ('"Xoá rule này"', '"Delete this rule"'),
        ('default: return "Cả hai";', 'default: return "Both";'),
        ('? "Regex không hợp lệ, rule này sẽ bị bỏ qua khi render"',
         '? "Invalid regex, this rule is skipped when rendering"'),
        (': "Mẫu cần tìm. Bật Regex để dùng biểu thức chính quy.");',
         ': "What to look for. Turn on Regex to use a regular expression.");'),
        ('setToolTipText("Bấm để đổi màu")', 'setToolTipText("Click to change the color")'),
    ],
    "ui/SyntaxColorEditor.java": [
        ('Buttons.secondary("Khôi phục mặc định", Icons.refresh(null, 14))',
         'Buttons.secondary("Reset all", Icons.refresh(null, 14))'),
        ('AccordionSection.caption("Màu áp dụng cho cả hai theme. "',
         'AccordionSection.caption("Colors apply to both themes. "'),
        ('+ "Bấm vào ô màu để đổi, bấm ↺ để trả một mục về mặc định.")',
         '+ "Click a swatch to change it, click the arrow to reset one entry.")'),
        ('? "Đang dùng bảng màu mặc định"', '? "Using the built-in palette"'),
        (': overrides + " mục đã chỉnh");', ': overrides + " overridden");'),
        ('"Trả về màu mặc định"', '"Reset to the default color"'),
        ('"Màu cho " + type.label() + " (" + type.labelEn() + ")"', '"Color for " + type.label()'),
        ('setToolTipText("Bấm để chọn màu")', 'setToolTipText("Click to pick a color")'),
    ],
    "ui/PreviewPanel.java": [
        ('announce("Đã trả bề rộng về mặc định")', 'announce("Card width back to the default")'),
        ('"Chưa có dữ liệu HTTP để hiển thị"', '"No HTTP data to show yet"'),
        ('"Mở Studio từ menu chuột phải trên một request trong Burp"',
         '"Open the studio from the right-click menu on a request in Burp"'),
    ],
    "ui/SuiteTabPanel.java": [
        ('notice.setState(false, "Chuột phải một request trong Proxy, Repeater hoặc Target, "\n'
         '                + "chọn \\"Open PoC Screenshot Studio\\"");',
         'notice.setState(false, "Right-click a request in Proxy, Repeater or Target, "\n'
         '                + "then choose \\"Open PoC Screenshot Studio\\"");'),
        ('badge.setText(real ? "Dữ liệu thật" : "Dữ liệu mẫu");',
         'badge.setText(real ? "Live traffic" : "Sample data");'),
    ],
    "ui/components/PromptDialog.java": [
        ('Buttons.secondary("Hủy", null)', 'Buttons.secondary("Cancel", null)'),
        ('Buttons.primary("Lưu", null)', 'Buttons.primary("Save", null)'),
    ],
}


def main():
    failures = []
    changed = 0
    for rel, pairs in EDITS.items():
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        for old, new in pairs:
            if old not in text:
                failures.append(f"{rel}: no match for {old[:70]!r}")
                continue
            text = text.replace(old, new)
            changed += 1
        path.write_text(text, encoding="utf-8")

    print(f"applied {changed} replacements")
    for f in failures:
        print("MISS " + f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
