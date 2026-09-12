# PoC Screenshot Studio for Burp Suite (Caido-style)

Extension cho Burp Suite: chụp Request/Response thành ảnh Proof of Concept sạch sẽ, đủ đẹp để dán thẳng vào báo cáo Pentest hoặc Bug Bounty. Thiết kế lấy cảm hứng từ plugin Screenshot của Caido.

Project: `D:\Tools\burp_extension\burp_screenshot_poc_project`

Toàn bộ nhãn trong giao diện là **tiếng Anh**. Tài liệu này giữ tiếng Việt; tên nút và tên mục được
ghi đúng như chữ hiện trên màn hình, trong dấu nháy.

---

## Tính năng

**Một workspace, ảnh và text cùng thấy một lúc**
- Ảnh render ở trên, khung text ở dưới, divider kéo được. Không còn hai tab tách rời phải bấm qua lại.
- Khung text **sửa được**. Sửa xong, ảnh render lại theo sau khoảng 150ms, caret không nhảy.
- Nút `Reset` trả về đúng dữ liệu Burp gốc.
- Thanh trên khung text có `Request` / `Response` và ô tìm kiếm `Search in the text (Ctrl+F)`.
- Nút `Text` trên toolbar có menu `Copy request`, `Copy response`, `Copy edited request`,
  `Copy edited response`, `Copy both`.

**Bảo mật thông tin trước khi chụp**
- Redaction theo regex hoặc chuỗi thường, ba kiểu che: `Blur`, `Blackout`, `Mask`.
- Hỗ trợ capture group: `Bearer\s+([A-Za-z0-9._-]+)` chỉ che phần token, giữ chữ `Bearer `.
- Rule mẫu sẵn một cú nhấp: `Bearer token`, `session_id`, `Cookie`, `password`, `api_key`, `JWT`, `SQLi`, `XSS`, `Admin`.
- Ô pattern có placeholder nói rõ regex dùng được, ví dụ
  `Regex or literal, e.g. Bearer\s+([A-Za-z0-9._-]+)`, kèm nhãn nhỏ `Regex supported`.

**Làm nổi bật bằng chứng**
- Highlight rule tô nền + viền quanh payload khai thác hoặc dữ liệu quan trọng.
- Highlight được vẽ trước, redaction vẽ sau, nên vùng che luôn đè lên vùng tô. Không thể vô tình để lộ token chỉ vì nó nằm trong một khung highlight.
- Highlight và redaction chạy lại trên **bản text đã sửa**, không phải bản gốc.

**Ảnh render**
- Bố cục `Side by Side` hoặc `Stacked`.
- Bề rộng `Compact (800px)` / `Medium (1000px)` / `Wide (1200px)` / `Full Width`.
- Scale `1x` / `2x` / `3x`. Ảnh được vẽ lại ở đúng scale, không phóng to bitmap, nên 2x nét thật.
- Cắt dòng `1-6, 15-30` cho request và response riêng.
- Thanh URL **tự xuống dòng** khi URL dài, tối đa 3 dòng, quá thì dòng cuối dùng dấu ba chấm giữa.
  Chiều cao card tăng theo số dòng. Chữ không còn ghi đè lên nhau.
- Thẻ card theo phong cách Caido: thanh URL, hairline phân mục, gutter số dòng, footer timestamp và
  kích thước response, bo góc và đổ bóng.

**Màu cú pháp**
- Màu lấy theo Burp Repeater, tách bạch từng thành phần: tên header, giá trị header, path, query key, query value, body.
- Sửa được ngay trong app tại `Settings` > `Syntax colors`, không cần build lại.

**Giao diện**
- Toolbar ở trên, ảnh trên text ở giữa, inspector dock bên phải với hai tab `Settings` và `Rules`.
- Theme tự theo Burp (dark/light), đổi trong khoảng 1 giây, không cần mở lại cửa sổ. Có thể ghi đè thủ công.
- Scrollbar mỏng, hover và focus ring rõ. Không đụng vào giao diện của Burp.

---

## Cài đặt vào Burp Suite

File JAR:

```
D:\Tools\burp_extension\burp_screenshot_poc_project\build\libs\burp-screenshot-poc.jar
```

1. Mở Burp Suite.
2. Vào `Extensions` > tab con `Installed`.
3. Bấm `Add`.
4. `Extension type`: chọn `Java`.
5. `Extension file`: trỏ tới file JAR ở trên.
6. `Next`, rồi `Close`.

Log khi nạp thành công:

```
PoC Screenshot Studio loaded.
  Right-click a request: Open PoC Screenshot Studio, or Quick Copy PoC Screenshot.
  A "PoC" tab is available in Repeater and Logger.
```

Khi cập nhật code: build lại, rồi vào `Installed` bấm `Reload` ở extension này.

---

## Sử dụng

### Mở Studio

Chuột phải một request trong Proxy history, Repeater, Logger hoặc Target, chọn `Extensions` > `Open PoC Screenshot Studio`, hoặc bấm `Ctrl + Shift + S`.

Cửa sổ mở ra với ảnh trên, khung text dưới, inspector bên phải. Chỉnh xong thì `Save PNG` hoặc `Copy image`.

**Từ Proxy history thì chuột phải là đường duy nhất.** Montoya API `2023.12.1` không có API phím
tắt toàn cục. `UserInterface` chỉ có `menuBar`, `registerSuiteTab`,
`registerContextMenuItemsProvider`, `registerHttpRequestEditorProvider`,
`registerHttpResponseEditorProvider`, `registerWebSocketMessageEditorProvider`, `createRawEditor`,
`createHttpRequestEditor`, `createHttpResponseEditor`, `createWebSocketMessageEditor`,
`applyThemeToComponent`, `currentTheme`, `currentEditorFont`, `currentDisplayFont`, `swingUtils`.
`setAccelerator` trên `JMenuItem` chỉ ăn khi menu đang mở, không phải phím tắt toàn cục. Nên
`Ctrl + Shift + S` chỉ có tác dụng khi cửa sổ Studio đang mở, còn lối vào từ Proxy history luôn là
menu chuột phải.

### Sửa text trong Studio

Khung text dưới ảnh gõ được. Sửa một header hay đổi dòng request thành `POST /x HTTP/2` thì ảnh đổi
theo, kể cả thanh URL và badge method. Nút `Reset` xóa toàn bộ sửa đổi và trả về dữ liệu Burp gốc.

Số dòng ở gutter tăng dần theo tài liệu, không phải số dòng của message gốc. Chèn hoặc xóa dòng sẽ
làm hai con số lệch nhau; đây là giới hạn đã biết, không phải lỗi.

### Copy nhanh

Chuột phải request, chọn `Extensions` > `Quick Copy PoC Screenshot (Default)`, hoặc bấm `Ctrl + Shift + C`. Ảnh render ở 2x theo template `Default` và vào clipboard ngay, không mở cửa sổ.

### Tab Screenshot PoC

Tab riêng trong Burp, dùng để chỉnh template và xem thử. Tab hiện badge `Sample data` khi chưa có request thật, và badge `Live traffic` sau khi bạn mở Studio từ một request.

### Tab PoC trong Repeater và Logger

Mở một request trong Repeater hoặc Logger: cạnh các tab editor có thêm tab `PoC`, hiện luôn ảnh
render của request đang xem. Tab có nút `Copy image`, `Save PNG`, `Open studio`, và một nút icon
fit khung (tooltip `Fit to the tab (Ctrl+0)`).

Chuyển sang request khác thì tab cập nhật theo. Tab này **không** xuất hiện ở Proxy intercept,
Intruder hay Scanner: provider trả `null` khi `ToolSource` không phải Repeater hoặc Logger.

Tab PoC chỉ đọc, không sửa request. `getRequest()` trả về đúng request nhận được và `isModified()`
luôn `false`, nên không có chuyện ảnh PoC làm lệch request thật.

### Phím tắt

Trong cửa sổ Studio:

| Phím | Tác dụng |
|---|---|
| `Ctrl + S` | `Save PNG` |
| `Ctrl + Shift + C` | `Copy image` vào clipboard |
| `F5` | Render lại |
| `Ctrl + 0` | Vừa khung nhìn |
| `Ctrl + =` / `Ctrl + +` | Phóng to |
| `Ctrl + -` | Thu nhỏ |
| `Ctrl + F` | Đưa con trỏ vào ô `Search in the text` |
| `Esc` | Đóng cửa sổ |

Trong tab `PoC` của Repeater và Logger: `Ctrl + S`, `Ctrl + Shift + C`, `F5`, `Ctrl + 0`,
`Ctrl + =`, `Ctrl + -`. Không có `Ctrl + F` và không có `Esc`.

`Esc` và phím tắt gắn cửa sổ chỉ hoạt động khi Studio mở dạng cửa sổ riêng. Trong tab Screenshot
PoC, phím tắt gắn với tab chứ không gắn toàn cục, để không cướp `Ctrl + S` của Burp khi bạn đang
làm việc ở Repeater. Tab `PoC` cũng vậy: binding nằm trên chính panel, chỉ ăn khi con trỏ đang ở
trong tab đó.

### Kéo mép để đổi bề rộng

Mép phải card có một **thanh kéo luôn hiện**: pill bo tròn ba chấm, vẽ đè lên ảnh ở cả hai mép. Rê
chuột vào mép, con trỏ đổi thành mũi tên ngang, kéo để đổi bề rộng wrap. Lúc kéo, badge hiện
`<số> px  ·  drag to resize`, và đường nét đứt hiện dọc mép. Nháy đúp để về mặc định.

Thanh kéo bám theo phép biến đổi ảnh sang component, nên ở zoom 200% nó vẫn nằm đúng mép card.

---

## Đối chiếu bảng màu

Bảng màu cú pháp lấy từ hai ảnh Burp Repeater thật. Mã hex được **đo bằng pixel**, không ước
lượng bằng mắt. Bảng đầy đủ và ảnh gốc nằm trong [reference/](reference/README.md).

Sau mỗi lần build, script test xuất hai ảnh để so trực tiếp với Repeater:

```
build\palette_dark.png
build\palette_light.png
```

Mỗi dòng là một thành phần: tên, chữ mẫu vẽ đúng màu và đúng font, và mã hex. Chỗ nào lệch thì
sửa tại `Settings` > `Syntax colors`, hoặc sửa thẳng `SyntaxPalette.defaults()` trong code.

Ba điểm đáng nhớ, đều đo được từ ảnh:

- Tên header và đường dẫn URL là **hai màu xanh khác nhau**. Nền sáng: `#000075` so với
  `#0000c0`. Nền tối: `#d1e8f9` so với `#bbcdff`.
- Giá trị (header value, query value) là màu trung tính ở nền sáng và xám sáng ở nền tối.
- Repeater cho path và query key **cùng một màu**. Bảng màu theo đúng như vậy. Ba nhóm header /
  URL / body thì vẫn khác màu nhau, đó mới là điều kiện để ảnh chụp dễ đọc.

Ảnh render hiện tại để so: `reference/after/poc-render-dark.png`, `poc-render-light.png`.
Ảnh bản cũ để so: `reference/before/`.

---

## Build từ source

Yêu cầu: một JDK từ 17 trở lên. Script tự dò bản mới nhất trong `C:\Program Files\Java`, hoặc dùng `JAVA_HOME` nếu biến này trỏ tới một JDK hợp lệ.

```powershell
.\build.ps1
```

hoặc:

```cmd
build.bat
```

Script làm bốn việc: biên dịch với `--release 17`, đóng gói Fat JAR kèm Gson, chạy bộ kiểm tra với `-ea`, và **dừng ngay nếu kiểm tra thất bại**. Bỏ `-ea` thì mọi `assert` bị JVM bỏ qua và một lượt build xanh không chứng minh gì.

Kết quả:

```
build\libs\burp-screenshot-poc.jar      # nạp vào Burp
build\test_poc_dark.png                 # ảnh render thử, nền tối
build\test_poc_light.png                # ảnh render thử, nền sáng
build\test_studio_merged.png            # toàn bộ workspace gộp, nền tối
build\test_studio_merged_light.png      # toàn bộ workspace gộp, nền sáng
build\palette_dark.png                  # bảng màu tham chiếu
build\palette_light.png
```

Hai ảnh `test_studio_merged*` là ảnh chụp cả cửa sổ, dùng để bắt lỗi bố cục và lỗi panel không
theo theme. Test quét từng pixel ngoài vùng card và fail nếu tìm thấy màu panel của look and feel,
nên một panel thiếu `getBackground()` sẽ làm build đỏ chứ không lọt ra bản phát hành.

Bản sao của các ảnh render nằm trong `reference/after/`, cạnh ảnh gốc và ảnh bản cũ để so.

---

## Ghi chú

- `burp-screenshot-poc.jar` ở thư mục gốc là bản build cũ, không phải bản script tạo ra. Nạp nhầm file này sẽ chạy code cũ. Bản đúng luôn nằm trong `build\libs\`.
- `lib\rsyntaxtextarea-3.4.0.jar` không còn được dùng từ bản refactor này. Giữ lại cũng không ảnh hưởng gì, xóa cũng được.
- `build.gradle` và `settings.gradle` vẫn còn trong project nhưng chưa được kiểm chứng: máy này không cài Gradle và project không có `gradlew`. Đường build chính thức là `build.ps1` và `build.bat`.
