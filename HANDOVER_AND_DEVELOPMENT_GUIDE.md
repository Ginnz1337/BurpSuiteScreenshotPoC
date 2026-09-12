# Bàn giao kỹ thuật: Burp Suite PoC Screenshot Studio

Tài liệu này mô tả kiến trúc, các quyết định thiết kế và những điểm bắt buộc phải giữ khi sửa code. Đọc hết mục 3 trước khi sửa bất cứ dòng nào.

Project: `D:\Tools\burp_extension\burp_screenshot_poc_project`
Sản phẩm: `build\libs\burp-screenshot-poc.jar` (khoảng 480 KB, Fat JAR kèm Gson).

---

## 1. Tổng quan

Extension Burp Suite viết bằng Montoya API `2023.12.1`, Java Swing + Java 2D. Biên dịch với `--release 17` để khớp JRE nhúng của Burp.

Chức năng: biến một HTTP Request/Response thành ảnh card sạch sẽ, có che thông tin nhạy cảm và tô sáng bằng chứng, để dán vào báo cáo.

Ba điểm vào:
- Context menu chuột phải: `Open PoC Screenshot Studio`, `Quick Copy PoC Screenshot (Default)`.
- Cửa sổ Studio (`StudioFrame`).
- Tab riêng trong Burp (`SuiteTabPanel`).

---

## 2. Cấu trúc mã nguồn

```
src\main\java\burp\screenshot\
├── BurpExtender.java              # Entry point Montoya: context menu, suite tab, theme source
│
├── model\                         # Dữ liệu thuần, không phụ thuộc Swing
│   ├── HttpExchangeData.java      # Request, response, URL, timing
│   ├── EditedExchange.java        # Bản text người dùng sửa, suy lại field dẫn xuất
│   ├── TemplateConfig.java        # Toàn bộ cấu hình một template
│   ├── HighlightRule.java         # Luật tô sáng
│   ├── RedactionRule.java         # Luật che
│   ├── ScopeTarget.java           # REQUEST / RESPONSE / BOTH
│   ├── LayoutMode.java            # SIDE_BY_SIDE / STACKED
│   └── RedactionMode.java         # BLUR / BLACKOUT / MASK
│
├── design\                        # Design system, không phụ thuộc ui\ và engine\
│   ├── Tokens.java                # Bất biến: khoảng cách, bán kính, font, màu. DARK và LIGHT
│   ├── Theme.java                 # Chế độ AUTO/DARK/LIGHT, nguồn theme từ Burp, listener
│   ├── SyntaxPalette.java         # TokenType -> Color, có bản DARK/LIGHT, đọc ghi được
│   ├── TokenType.java             # 36 loại thành phần được tô màu riêng
│   └── Icons.java                 # Icon vector vẽ bằng Java 2D
│
├── ui\
│   ├── components\                # Component kit, tất cả màu giải ở lúc vẽ
│   │   ├── Buttons.java           # primary / secondary / ghost / danger / iconOnly
│   │   ├── Fields.java            # text, password, area, combo, label, scroll, capHeight
│   │   ├── SegmentedControl.java  # Chọn một trong n, thay cho cặp toggle
│   │   ├── AccordionSection.java  # Mục gập được trong inspector
│   │   ├── SwitchToggle.java      # Công tắc 34x18, có animation
│   │   ├── CardPanel.java         # Nền card bo góc
│   │   ├── SlimScrollBarUI.java   # Scrollbar mỏng, cài cho từng JScrollPane
│   │   ├── Toast.java             # Thông báo nổi, tự ẩn
│   │   └── PromptDialog.java      # Hộp thoại nhập một dòng, vẽ bằng token
│   ├── StudioPanel.java           # Toàn bộ studio, dùng chung cho cả hai vật chủ
│   ├── StudioFrame.java           # Bọc StudioPanel trong JFrame
│   ├── SuiteTabPanel.java         # Bọc StudioPanel trong tab Burp, kèm badge dữ liệu
│   ├── PocEditorPanel.java        # Tab PoC trong Repeater và Logger, chỉ đọc
│   ├── PreviewPanel.java          # Ảnh xem trước, zoom, fit, kéo mép đổi bề rộng
│   ├── InspectorPanel.java        # Dock bên phải, hai tab Settings và Rules
│   ├── RuleCard.java              # Một rule highlight hoặc redaction
│   ├── TextStylePanel.java        # Nửa dưới workspace: JTextPane sửa được + gutter
│   └── SyntaxColorEditor.java     # Lưới token kèm swatch, sửa màu trong app
│
└── engine\                        # Không được import burp.screenshot.ui.*
    ├── ScreenshotRenderer.java    # Điều phối: đo, dựng layout, vẽ
    ├── RenderContext.java         # Config + font + metrics + palette + pattern đã compile
    ├── CardChrome.java            # Thanh URL, footer, đổ bóng, bo góc, watermark
    ├── TokenWrap.java             # Ngắt dòng token, dùng chung cho body và thanh URL
    ├── SectionPainter.java        # Gutter, số dòng, span, wrap, tính rect cho rule
    ├── SyntaxHighlighter.java     # Cắt chuỗi thành List<Token>
    ├── TextProcessor.java         # Lọc header, cắt khoảng dòng, chèn dấu lược bỏ
    ├── TemplateManager.java       # Đọc ghi JSON, normalize()
    ├── BlurFilter.java            # Làm mờ vùng ảnh
    └── ClipboardHelper.java       # Đẩy BufferedImage vào clipboard hệ thống
```

---

## 3. Quy tắc bắt buộc

Chín quy tắc dưới đây là nguyên nhân gốc của gần như mọi lỗi đã sửa trong bản refactor này. Vi phạm là lỗi quay lại.

### 3.1. Không bao giờ gọi `UIManager.put` hay `updateComponentTreeUI`

Extension chạy chung một JVM với Burp. `UIManager.put` đổi default toàn cục, tức là đổi luôn scrollbar và màu của Proxy, Repeater, Intruder. Cách đúng là cài `SlimScrollBarUI.install(scrollPane)` cho **từng** `JScrollPane` của mình.

### 3.2. Màu phải giải ở lúc vẽ, không phải lúc khởi tạo

Không lưu `Color` vào field của component. Ghi đè `getBackground()` / `getForeground()` và trả về `Theme.tokens().<tên>`. Swing gọi `getBackground()` mỗi lần vẽ, nên đổi theme là đổi màu ngay, không cần dựng lại cây component.

Ngoại lệ duy nhất là field final kiểu `int` như `Tokens.SM`, những giá trị đó không phụ thuộc theme.

### 3.3. Getter màu phải chịu được null trong constructor

Constructor của `JComponent` chạy `updateUI` của look and feel, và `installColors` gọi `getForeground()` / `getBackground()` **trước khi** field của lớp con được gán. Mọi override đọc field phải có nhánh dự phòng:

```java
@Override public Color getForeground() {
    if (kind == null) return Theme.tokens().textPrimary;   // cửa sổ constructor
    ...
}
```

Giá trị dự phòng phải khác null, nếu không look and feel sẽ cài màu của chính nó vào. Đây là lỗi thật đã làm `Fields.ThemedLabel` ném NPE ở mọi lần dựng.

### 3.4. `BoxLayout` sẽ kéo giãn mọi thứ

Một container Swing báo `maximumSize` vô hạn, và `BoxLayout` dùng hết chỗ trống để kéo con lên tới mức đó. Kết quả là công tắc bị kéo thành hình oval và label trôi giữa khoảng trống. Trong cột dọc, bọc mọi hàng bằng `Fields.capHeight(...)`, hoặc ghi đè `getMaximumSize()`.

### 3.5. Kích thước trong panel cuộn phải đo qua `JViewport`

`getWidth()` của một panel nằm trong `JScrollPane` ít nhất bằng bề rộng nội dung nó chứa. Đo bằng `getWidth()` sẽ khiến "vừa khung nhìn" giữ nguyên zoom hiện tại thay vì chọn zoom, và preset `Full Width` phình ra sau mỗi lần render. Dùng `PreviewPanel.visibleWidth()`, nó lấy bề rộng của `JViewport` bao ngoài.

### 3.6. `engine\` không được import `ui\`

Tầng render phải chạy được mà không cần Swing component nào, đó là điều kiện để test headless kiểm tra được ảnh. `design\` là tầng dùng chung, cả hai đều được import.

### 3.7. Panel nào có nền thì phải override `getBackground()`

Một `JPanel` không override sẽ vẽ màu panel của look and feel. Trong Burp màu đó **gần giống** token
nên lỗi này gần như vô hình khi nhìn bằng mắt, và chỉ lộ ra khi render ra ảnh rồi đo pixel. Trong
test headless không có look and feel nào được cài, nên nó thành `Panel.background` của Metal,
`#eeeeee`, và hiện thành một dải sáng trong theme tối.

Quy tắc: mọi panel mang nền, kể cả toolbar và thanh trạng thái, override `getBackground()` trả về
token, và trả về **theo từng lần gọi**. Panel nào không mang nền thì để `setOpaque(false)` và đừng
override gì cả, để nền của cha lộ ra.

`ScreenshotVerificationTest.checkNoUnthemedGround` là lưới bắt lỗi này: nó render cả hai theme,
quét từng pixel ngoài vùng card, và fail kèm số pixel, toạ độ 6 điểm đầu và bounding box. Toạ độ
quan trọng hơn con số, vì dải nào sáng mới nói panel nào thiếu.

### 3.8. `setBorder(null)` phải gọi SAU `setUI(...)`, không bao giờ trước

`JComponent.setUI(ui)` gọi `ui.installUI(this)`, và `BasicSplitPaneUI.installDefaults()` cài lại
`SplitPane.border` của look and feel. Gọi `setBorder(null)` trước đó thì nó bị ghi đè ngay sau.

`BasicBorders$SplitPaneBorder.paintBorder` vẽ đường viền bằng `JSplitPane.getLeftComponent().getBackground()`,
tức là màu panel của look and feel, cộng hai màu `highlight` và `shadow`. Kết quả là một đường viền
sáng quanh cả hai nửa split, 8 pixel, chỉ nhìn thấy khi đo. Đây là lỗi đã mất một buổi để truy: nó
sống sót qua mọi lần kiểm tra bằng mắt.

### 3.9. Hàm cài đặt dùng chung không được ép `setOpaque(true)`

`SlimScrollBarUI.install` từng gọi `pane.getViewport().setOpaque(true)`, đè lên `setOpaque(false)`
mà panel gọi trước đó. Viewport sau đó vẽ màu panel của look and feel ở dải bên cạnh phần nội dung
hẹp hơn nó, thành một dải sáng chạy dọc khung text. Một hàm tiện ích cài cho người khác thì chỉ được
đặt những gì người gọi không nói tới, và ở đây người gọi đã nói rồi.

---

## 4. Luồng render

`ScreenshotRenderer.render(exchange, config, baseWidth, scale, dark)` chạy theo thứ tự:

1. **Đo trên bề mặt nháp.** Một `BufferedImage` 1x1 với `RenderContext.applyHints`. Hints phải giống hệt bề mặt vẽ thật, nếu không bên đo và bên vẽ bất đồng về bề rộng chuỗi và chữ lệch dần về cuối dòng.
2. `TextProcessor` lọc header ẩn và cắt khoảng dòng.
3. `SectionPainter.layout` wrap tham lam theo `FontMetrics`, ưu tiên ngắt ở dấu cách, phẩy, chấm phẩy, `&`, `?`, `/`, `-`, cuối cùng mới ngắt ký tự. Phần ngắt dòng nằm trong `TokenWrap`, dùng chung với thanh URL.
4. **`CardChrome.urlLayout` chạy trước khi chốt chiều cao card.** Địa chỉ wrap được nên số dòng của nó là một phần của `cardHeight`; đo ở pha vẽ thì thanh URL sẽ đè lên phần thân. `urlLayout` trả cả `lines` lẫn `height`, và chính object đó được truyền xuống `paintUrlBar`, nên đo và vẽ không thể lệch nhau. Bar cao `max(URL_BAR_HEIGHT, lines * lineHeight + padding * 2)`, tối đa `MAX_URL_LINES` dòng; quá thì dòng cuối dùng dấu ba chấm giữa.
5. Tính `cardWidth`, `bodyHeight`, rồi kích thước ảnh. **`baseWidth` là đơn vị pixel thiết bị**, nên `scale` nhân cả canvas; đơn vị layout không mang scale.
6. Vẽ: đổ bóng, nền card, clip theo hình card, thanh URL, hai mục Request/Response, watermark.
7. **Highlight vẽ trước, redaction vẽ sau.** Redaction là phát ngôn mạnh hơn, phải được phép đè lên highlight. Đảo thứ tự này là lỗi bảo mật: token bị che sẽ bị khung highlight vẽ lại lên trên.
8. Blur chạy trước blackout để blackout không bị nhoè bởi vùng blur bên cạnh.
9. Footer: timestamp, kích thước response, thời gian phản hồi.

Đo bề rộng dòng phải cộng theo `FontMetrics` của **đúng font từng token**. Với font mono hiện tại (JetBrains Mono), bold và regular có advance giống nhau nên lỗi này không quan sát được; nó sẽ thành lỗi thật vào ngày đổi sang font tỷ lệ. Test in rõ điều này thay vì báo pass như một bằng chứng mạnh.

---

## 5. Design system

### Tokens

Bất biến, hai thực thể `Tokens.DARK` và `Tokens.LIGHT` dựng qua `Builder`. Gồm khoảng cách `XS/SM/MD/LG/XL`, bán kính `R_SM/R_MD/R_LG`, font `ui`, `uiBold`, `uiSmall`, `uiSmallBold`, `title`, `code`, `codeBold`, `codeItalic`, và bảng màu.

Chọn font có kiểm tra: nếu `Consolas` hoặc `Segoe UI` không tồn tại, Java ánh xạ sang một font tỷ lệ và số dòng trong gutter mất canh phải. `Tokens.pick` kiểm tra `GraphicsEnvironment.getAvailableFontFamilyNames()` rồi mới chọn, fallback `Font.MONOSPACED` / `Font.SANS_SERIF`.

Accent là đỏ Burp `#b91c3e`.

### Theme

```java
Theme.setSource(Source)      // BurpExtender cắm api.userInterface() vào
Theme.setMode(Mode)          // AUTO | DARK | LIGHT
Theme.getMode()              // chế độ đang chọn
Theme.isDark()               // kết quả sau khi phân giải AUTO
Theme.tokens()               // Tokens của theme hiện tại
Theme.tokensFor(boolean)     // Tokens của một theme bất kỳ, cho renderer
AutoCloseable addListener(Runnable)
```

Montoya **không bắn sự kiện** khi người dùng đổi theme. `Theme` chạy một `javax.swing.Timer` 1000 ms gọi `currentTheme()` và chỉ phát sự kiện khi giá trị thật sự đổi. Đó là lý do đổi theme trong Burp mất tối đa một giây mới thấy Studio đổi theo.

Mọi listener phải được đóng. `StudioPanel` đăng ký trong `addNotify` và đóng trong `removeNotify`, và `dispose()` idempotent để `windowClosed` gọi lại lần nữa cũng không sao.

### SyntaxPalette

`TokenType` có 32 giá trị, chia nhóm: dòng yêu cầu, thành phần URL, header, dòng trạng thái, body (JSON/HTML/form), và nhóm cấu trúc (chữ thường, chữ mờ, số dòng, dấu lược bỏ). Mỗi giá trị mang một nhãn tiếng Anh và một dòng gợi ý tiếng Anh, dùng làm tooltip trong `Syntax colors`. Bản trước có thêm `labelVi`; đã bỏ, giao diện chỉ còn tiếng Anh.

`SyntaxPalette.DARK` và `.LIGHT` là bảng mặc định. `SyntaxPalette.empty()` là dạng lưu của "không có ghi đè nào": template chỉ lưu những token người dùng thật sự đổi, nên một template sửa ở nền tối vẫn nhận bảng màu sáng dựng sẵn khi Burp chuyển sang nền sáng. Ghi đè nằm trong `TemplateConfig.syntaxColors` dưới dạng hex, không lưu `java.awt.Color` vì Gson ghi kiểu này bằng reflection và hỏng trên JDK có module.

`SyntaxPalette.isBold(type)` là nguồn duy nhất quyết định token nào in đậm.

Giá trị mặc định **đo từ ảnh Burp Repeater thật**, không ước lượng. Cách đo và bảng hex đầy đủ ở
`reference/README.md`. Ba kết luận quan trọng, vì chúng ngược với trực giác:

- Tên header và path URL là hai màu xanh **khác nhau**. Nền sáng `#000075` so với `#0000c0`; nền tối `#d1e8f9` so với `#bbcdff`.
- Repeater cho path và query key **cùng màu**. Đừng tách chúng ra; `checkTokenSeparation` trong test cố tình không assert hai token này khác nhau.
- Repeater tô cả dòng trạng thái bằng một màu, nên `200` và `500` trông giống nhau. Bảng màu cố ý đi khác chỗ này: bốn lớp status giữ màu ngữ nghĩa để ảnh PoC còn phân biệt được. Đây là chỗ duy nhất cố ý lệch khỏi Repeater.

Sửa màu thì sửa `SyntaxPalette.defaults()`, rồi build lại. `build\palette_dark.png` và
`palette_light.png` là bảng swatch để đối chiếu tay.

---

## 6. Giao diện

### StudioPanel dùng chung cho hai vật chủ

`StudioPanel` chứa toàn bộ studio. `StudioFrame` bọc nó trong một `JFrame`, `SuiteTabPanel` bọc nó trong tab Burp. Nhờ vậy "thấy gì lưu nấy" không thể lệch giữa hai nơi.

Khác biệt duy nhất là phạm vi phím tắt, tham số `globalShortcuts`:

```java
public StudioPanel(TemplateManager templateManager, HttpExchangeData data, boolean globalShortcuts)
```

Trong tab, root pane thuộc cửa sổ chính của Burp. Một binding `WHEN_IN_FOCUSED_WINDOW` trên đó sẽ bắn cả khi người dùng đang gõ ở Repeater, tức là cướp `Ctrl + S` của Burp. Nên vật chủ nhúng bind vào chính nó, còn vật chủ cửa sổ bind toàn cục. `Esc` theo cùng luật, vì trong tab nó sẽ có nghĩa là "đóng Burp".

Bố cục: toolbar ở trên, `JSplitPane` ngang ở giữa, thanh trạng thái ở dưới. Nửa trái của split ngang là một `JSplitPane` **dọc**: ảnh ở trên, khung text ở dưới, `resizeWeight` 0.62. Nửa phải là inspector, rộng 360, tối thiểu 320. Cả hai divider được đặt vị trí đúng một lần, khi panel đã có kích thước thật.

Trước đây ảnh và text là hai tab `CardLayout` tách rời, không bao giờ thấy nhau. Gộp lại thì copy text theo ảnh không còn phải bấm qua lại.

### Sửa text, ảnh đổi theo

`HttpExchangeData` lưu `url`, `httpMethod`, `httpVersion`, `statusCode`, `statusReason` thành field riêng, không suy ra từ raw text. Renderer lại đọc raw text qua `TextProcessor`. Nên thay raw text thôi là chưa đủ: thanh URL và dòng trạng thái sẽ vẫn hiện giá trị cũ.

`model\EditedExchange` giữ bản gốc cộng override riêng cho từng phía, và `effective()` trả bản copy đã thay raw text **cùng các field dẫn xuất**: dòng request thành `httpMethod` + `httpVersion`, URL dựng từ header `Host` cộng target, dòng status thành `statusCode` + `statusReason`, `responseSizeBytes` tính lại từ độ dài raw response đã sửa. `StudioPanel` đẩy `effective()` vào preview, nên ảnh, highlight và redaction đều chạy trên bản đã sửa. `engine\` không phải sửa gì.

`TextStylePanel` sửa được với hai ràng buộc:

- **Không gọi `rebuild()` khi người dùng gõ.** `rebuild()` xóa sạch document và đặt caret về 0, nên gõ sẽ nhảy về đầu dòng. Thay vào đó tô lại đúng dòng vừa sửa: lấy element theo offset, tokenize lại dòng đó, `setCharacterAttributes`.
- **Debounce 150ms** trước khi báo cho chủ, để mỗi phím gõ không kéo theo một lần render.

Gutter số dòng giả định một dòng document ứng một dòng message gốc. Chèn hoặc xóa dòng phá giả định đó. Xử lý bằng cách suy `LineKind` theo vị trí và chấp nhận gutter tăng dần; đây là giới hạn đã biết, ghi trong README.

### InspectorPanel

Hai tab qua `SegmentedControl` và `CardLayout`.

Tab `Settings`, các mục gập: `Template` (chọn, `Reload`, `Save`, `Save as new`), `Hidden headers`, `Layout`, `Card style`, `Display`, `Line ranges`, `Syntax colors`.

Tab `Rules`: hai nhóm Highlight và Redaction, mỗi nhóm có nút `Add` và menu `Presets` lọc theo `RuleCard.Preset.isHighlight()`.

Cờ `ready` chặn callback trong lúc constructor dựng control. Không có nó, panel bắn `configChanged` trước khi vật chủ kịp nối dây.

**Bề rộng inspector là ràng buộc cứng: 360 px, phần nội dung còn 332.** `Buttons.FlatButton` trả
`getMinimumSize()` bằng `getPreferredSize()`, nên `BoxLayout` **không co được** nút và nút cuối bị
cắt cụt ở mép panel chứ không xuống dòng. Ba nút `Reload` + `Save` + `Save as new` cần 376 px và đã
bị cắt đúng như vậy; `Reload` nay nằm cạnh combo mà nó nạp lại, hàng nút còn `Save` + `Save as new`
= 255 px. Thêm nút vào hàng thì đo lại trước: `Presets` + `Add` là 222 px, còn chỗ.

### PreviewPanel

Ảnh xem trước được **render lại ở đúng scale**, không phóng to bitmap 1x. Zoom 200% vì thế vẫn nét. Nền caro vẽ trong `paintComponent` phía sau ảnh, không bake vào `BufferedImage`, nếu không vùng trong suốt sẽ mất khi lưu PNG. Kéo mép phải đổi bề rộng wrap, nháy đúp reset.

Thanh kéo ở mép card **luôn hiện**, không chỉ khi hover: pill bo tròn ba chấm, alpha 55%, lên 90% khi hover hoặc đang kéo. Vị trí suy từ phép biến đổi ảnh sang component nên bám đúng mép card ở mọi mức zoom. Đường nét đứt khi hover vẫn giữ, grip chỉ làm chỗ kéo nhìn thấy được.

### PocEditorPanel

Tab `PoC` trong Repeater và Logger. Dựng ảnh cỡ gọn bằng chính `PreviewPanel`, kèm `Copy image`, `Save PNG`, `Open studio`, `Fit to the tab` và một dòng thống kê.

`BurpExtender.PocRequestEditorProvider` trả `null` khi `EditorCreationContext.toolSource()` không phải Repeater hoặc Logger, nên tab không xuất hiện ở Proxy intercept, Intruder hay Scanner. Provider phải trả `null` chứ **không được ném exception**, vì Burp gọi nó cho nhiều context khác nhau, và mỗi lần gọi phải dựng editor mới, không dùng chung một instance.

Editor chỉ đọc: `getRequest()` trả đúng request nhận được, `isModified()` luôn `false`. Phím tắt bind trên chính panel với `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT`, nên chỉ ăn khi con trỏ đang ở trong tab, không cướp `Ctrl + S` của Repeater.

### SyntaxColorEditor

Lưới token kèm swatch, bấm để mở `JColorChooser`, có nút khôi phục mặc định. Đây là lối thoát cho sai số của bảng màu: người dùng sửa được ngay trong app, không cần build lại.

---

## 7. Build và kiểm tra

```powershell
.\build.ps1        # hoặc build.bat
```

Bốn bước: dò JDK mới nhất trong `C:\Program Files\Java` (hoặc `JAVA_HOME`), biên dịch `--release 17`, đóng gói Fat JAR kèm Gson, chạy test với `-ea` và **dừng build nếu test hỏng**.

`-ea` không phải tùy chọn. Mọi kiểm tra trong `ScreenshotVerificationTest` là `assert`, JVM bỏ qua toàn bộ nếu thiếu cờ, và một lượt build xanh sẽ không chứng minh gì.

Test là một chương trình `main`, không phải JUnit, nên không cần thư viện test nào. Nó chạy headless và kiểm tra:

| Kiểm tra | Nội dung |
|---|---|
| sample exchange | Dữ liệu mẫu có URL, request, response |
| templates | Ba template dựng sẵn đọc được |
| text processing | Lọc header và cắt dòng chạy |
| advance theo font từng token | Bề rộng dòng cộng đúng theo font mỗi token |
| rendered dark/light | Render ra ảnh, hai theme khác nhau trên 30% số pixel |
| URL wrap | URL 300 ký tự cho tối đa `MAX_URL_LINES` dòng, bar cao hơn `URL_BAR_HEIGHT` |
| URL cap | Vượt `MAX_URL_LINES` thì dòng cuối dùng dấu ba chấm giữa |
| scale consistency | Canvas 2x gấp đôi, đơn vị layout không mang scale |
| theme listener removal | Listener đóng rồi thì không bắn nữa |
| config repair | `normalize()` thay null bằng default của constructor |
| token separation | Tên header, thành phần URL, thành phần body khác màu nhau |
| palette reference | Xuất ảnh bảng màu |
| panels constructed | Mọi panel dựng được không cần màn hình |
| studio window | Studio dựng được trong `JFrame` |
| merged workspace | Ảnh trên text trong cùng một split; sửa text thì `effective()` đổi theo |
| text editing | Tô lại đúng dòng, có báo chủ, `Reset` trả về bản gốc |
| edited exchange | Method, version, URL và status đi theo dòng text đã sửa |
| preview grip | Grip vẽ ở cả hai mép, đúng toạ độ sau khi zoom |
| PoC tab scope | Provider chỉ trả editor cho Repeater và Logger |
| no unthemed ground | Không pixel nào ngoài vùng card mang màu panel của look and feel, ở **cả hai** theme |

Kiểm tra cuối là lưới bắt lỗi của quy tắc 3.7. Nó render cả cửa sổ ở 1400x900 trong hai theme,
quét từng pixel ngoài vùng card, và fail kèm số pixel, sáu toạ độ đầu và bounding box. Vùng card
được loại trừ vì đó là ảnh chứ không phải mặt Swing: khử răng cưa của chữ chạy qua mọi mức xám,
kể cả màu panel của look and feel.

Ảnh xuất ra `build\`: `test_poc_dark.png`, `test_poc_light.png`, `test_studio_merged.png`,
`test_studio_merged_light.png`, `palette_dark.png`, `palette_light.png`.

### Những việc phải kiểm tra tay trong Burp

Test headless không phủ được những mục sau. Chạy lại danh sách này sau mỗi lần sửa UI:

1. Nạp JAR, tab `Screenshot PoC` hiện ra, không còn ký tự emoji nào, và không còn chuỗi tiếng Việt nào trong giao diện.
2. Tab hiện badge `Sample data` khi chưa có request thật, đổi thành `Live traffic` sau khi mở Studio từ một request.
3. Chuột phải request, mở Studio: inspector bên phải, không control nào tràn chữ, scrollbar mỏng, hover và focus ring rõ.
4. Ảnh trên, text dưới, cùng thấy một lúc. Kéo divider dọc được. Sửa một header trong khung text: ảnh đổi theo sau khoảng 150ms, caret không nhảy về đầu. Sửa dòng request thành `POST /x HTTP/2`: thanh URL và badge method trong ảnh đổi theo. `Reset` trả về đúng dữ liệu Burp gốc.
5. Highlight và redaction vẫn ăn trên bản text đã sửa, không phải bản gốc.
6. Mở một request trong Repeater và trong Logger: tab `PoC` xuất hiện ở cả hai, hiện đúng request đang xem, `Copy image` và `Save PNG` chạy. Chuyển request khác thì tab cập nhật theo.
7. Mở Proxy intercept và Intruder: **không** có tab `PoC`.
8. Đổi theme Burp qua lại: Studio và tab PoC đổi màu trong vòng một giây, không cần mở lại cửa sổ.
9. Mở đóng Studio 5 lần, kiểm tra không rò rỉ listener.
10. **Kiểm tra Burp không bị ảnh hưởng**: scrollbar của Proxy history và Repeater vẫn nguyên như trước khi nạp extension.
11. Lưu ảnh PNG và copy clipboard ở 2x, ảnh sắc nét, kích thước đúng.
12. Thanh kéo ở mép card thấy sẵn từ trước khi rê chuột. Kéo đổi bề rộng, nháy đúp về mặc định, `Ctrl + cuộn`, `Ctrl + 0`. Ở zoom 200% chữ vẫn nét và grip vẫn nằm đúng mép card.
13. URL dài vài trăm ký tự: thanh URL xuống dòng, tối đa 3 dòng, không chồng chữ, đuôi URL còn đọc được.
14. Template JSON cũ trong `~/.burp_poc_screenshot_templates.json` vẫn đọc được, field mới nhận default.
15. Inspector: không nhãn nút nào bị cắt cụt, combo `Current` hiện mũi tên, nút reload icon nằm cạnh nó.
16. Hộp thoại `Save as new` hiện đúng màu theme, không phải hộp thoại xám của Metal.

---

## 8. Đã xóa so với bản trước

Tám file cũ đã bị xóa hẳn, không còn tham chiếu nào: `ScreenshotStudioFrame`, `SidebarPanel`, `PreviewCanvasPanel`, `SelectableCodeViewPanel`, `RuleItemPanel`, `UIFactory`, `VectorIcons`, `ui/Theme.java`.

Thay thế tương ứng: `StudioFrame`, `InspectorPanel`, `PreviewPanel`, `TextStylePanel`, `RuleCard`, `ui/components/*`, `design/Icons`, `design/Theme`.

Bản cũ có những lỗi đã sửa dứt điểm, ghi lại để không tái phát:

- `ui/Theme.java` trộn hằng số tĩnh với getter động; ba panel đọc màu lúc khởi tạo nên đổi theme không repaint. Nay màu giải ở lúc vẽ.
- Renderer đo `FontMetrics` trên bề mặt 1x1 không có fractional metrics, còn bề mặt vẽ thì bật. Chữ lệch dần về cuối dòng. Nay hai bên dùng chung `RenderContext.applyHints`.
- Gutter số dòng bị clip che mất.
- Highlight vẽ đè lên redaction.
- Đổ bóng lồng 16 lớp `fillRoundRect` SRC_OVER, alpha cộng dồn thành mép card đậm gần 70% thay vì khoảng 8%.
- `BlurFilter` chia sẻ raster qua `getSubimage` và không chặn `rx + rw > width`.
- `Pattern.compile` gọi lại trong vòng lặp từng dòng. Nay compile một lần trong `RenderContext`.
- `SuiteTabPanel.setExchangeData()` không được gọi ở đâu, tab luôn hiện dữ liệu mẫu.
- `ScreenshotVerificationTest` ghi ra một đường dẫn tuyệt đối của máy khác và ném `FileNotFoundException`; cả hai script build đều thiếu `-ea`.
- `build.ps1` và `build.bat` dò `jdk-25.0.4.1`, `jdk-17`, những bản không tồn tại trên máy này.

Lần gộp workspace này xóa thêm: `enum View` cùng `centreCards`, `viewSwitch` và `showView` trong
`StudioPanel` (hai tab ảnh/text tách rời), và `labelVi` trong `TokenType`. Ảnh và text nay nằm
trong một `JSplitPane` dọc. Lỗi sửa trong lần này, ghi lại để không tái phát:

- **220975 pixel màu panel của look and feel trong ảnh render nền tối.** Nguyên nhân là
  `SlimScrollBarUI.install` ép `setOpaque(true)` lên viewport, đè lên `setOpaque(false)` của panel
  gọi nó. Xem quy tắc 3.9.
- **8 pixel viền sáng quanh cả hai nửa split.** `BasicSplitPaneUI.installDefaults` cài lại
  `SplitPane.border` sau `setBorder(null)`. Xem quy tắc 3.8.
- **Thanh URL ghi đè chính nó.** `drawMiddleEllipsis` không clip và không trừ bề rộng dấu ba chấm
  khỏi ngân sách đuôi, nên đầu, dấu ba chấm và đuôi vẽ chồng lên nhau. Nay có clip và ngân sách
  chia dứt khoát.
- **Thanh kéo mép gần như vô hình.** `paintEdgeHint` chỉ vẽ khi đã hover. Nay grip vẽ luôn.
- **Ô pattern không nói regex dùng được.** Nay có placeholder.
- **Nút `Save as new` và mũi tên của combo `Current` bị cắt cụt ở mép inspector.** Hàng ba nút cần
  376 px trong khi panel cho 332 px, và `FlatButton` không co được. Nay `Reload` là nút icon nằm
  cạnh combo. Xem mục InspectorPanel.

---

## 9. Việc còn lại và hướng phát triển

**Còn nợ**

- Bảng màu cú pháp đã đo bằng pixel từ hai ảnh Repeater, bảng đầy đủ ở `reference/README.md`. `build\palette_dark.png` và `palette_light.png` sinh ra để đối chiếu tay sau mỗi lần build. Chỗ nào lệch thì sửa trong `Settings` > `Syntax colors` hoặc sửa `SyntaxPalette.defaults()`.
- `HEADER_COLON` đang tô theo màu chữ thường. Ảnh Repeater không tách được dấu `:` khỏi tên header ở độ phân giải này, nên chưa kết luận được. Muốn chắc thì chụp lại ở mức phóng to hơn.
- **Gutter số dòng không còn khớp message gốc sau khi sửa text.** Chèn hoặc xóa dòng thì số ở gutter vẫn tăng dần theo tài liệu. Sửa được thì phải đánh số lại theo `LineKind` đã suy, nhưng như vậy số sẽ nhảy khi gõ. Đã chấp nhận và ghi trong README.
- `burp-screenshot-poc.jar` ở thư mục gốc là bản build cũ từ trước refactor, nên xóa để không nạp nhầm.
- `lib\rsyntaxtextarea-3.4.0.jar` và hai file Gradle không còn được dùng. Máy này không cài Gradle và project không có `gradlew`, nên `build.gradle` chưa từng được chạy thật.

**Hướng mở rộng**

- Watermark đã có trường và đã vẽ được, có thể thêm lựa chọn vị trí và độ mờ.
- Cho người dùng chọn font mono nếu máy có cài JetBrains Mono, Fira Code, Cascadia Code.
- Kéo bôi đen trực tiếp trên preview để điền khoảng dòng vào ô cắt dòng.
- Xuất thêm SVG hoặc HTML ngoài PNG.
