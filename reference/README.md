# Ảnh đối chiếu

Thư mục này giữ ảnh gốc và ảnh render để so màu, so bố cục. Không phải tài nguyên build.
Không file nào ở đây được nhúng vào JAR.

## Cấu trúc

```
reference/
├── burp-repeater/     ảnh Burp Repeater người dùng gửi, dùng làm chuẩn màu
│   └── zoom/          crop phóng 3x của chính ảnh trên, dùng để đọc màu
├── before/            giao diện và ảnh render của bản CŨ, trước refactor
├── after/             ảnh render hiện tại, sinh bởi ScreenshotVerificationTest
└── archive/           ảnh trung gian và ảnh không liên quan
```

## burp-repeater/

| File | Kích thước | Nền | Nội dung |
|---|---|---|---|
| `repeater-light.png` | 2080x751 | `#ffffff` | Burp Repeater, theme sáng |
| `repeater-dark.png` | 2088x741 | `#2b2b2b` | Burp Repeater, theme tối |
| `repeater-light-downscaled.jpg` | 2000x722 | `#ffffff` | Bản thu nhỏ của ảnh sáng |
| `repeater-dark-downscaled.jpg` | 2000x710 | `#2b2b2b` | Bản thu nhỏ của ảnh tối |

Hai ảnh `*-downscaled.jpg` là bản dán vào hội thoại ở lần gửi sau. Ảnh trong transcript bị lưu ở
đúng độ phân giải hiển thị, không phải độ phân giải gốc, nên **chỉ đo màu trên `repeater-light.png`
và `repeater-dark.png`**. Tên file không nói lên theme: theme được xác định bằng màu nền đo được,
không bằng mắt thường.

Mỗi ảnh có hai pane: request bên trái, response bên phải. Ảnh sáng là file `repeater-light.png`.

### zoom/

Crop 3x để đọc màu từng token. Đây là bằng chứng của bảng màu bên dưới.

| File | Vùng |
|---|---|
| `zoom-request-line-light.png` | dòng request đầu, theme sáng |
| `zoom-request-line-dark.png` | dòng request đầu, theme tối |
| `zoom-html-body-light.png` | dòng HTML đầu của body, theme sáng |
| `zoom-html-body-dark.png` | dòng HTML đầu của body, theme tối |
| `pane-light.png` | 10 dòng header của response, theme sáng |
| `pane-dark.png` | 10 dòng header của response, theme tối |

## Bảng màu đo được

Đo bằng `tools/probe_runs.py` trên `repeater-light.png` và `repeater-dark.png`. Mọi giá trị là hex
đọc trực tiếp từ pixel, không phải ước lượng bằng mắt.

| Token | Light | Dark |
|---|---|---|
| method (`POST`) | `#141414` | `#d1e8f9` |
| path, query key | `#0000c0` | `#bbcdff` |
| dấu `=` `&` | `#141414` | `#d1e8f9` |
| query value | `#a01010` | `#a5c35b` |
| header name | `#000075` | `#d1e8f9` |
| header value | `#202020` | `#bababa` |
| số dòng | `#787878` | `#a0a0a0` |
| status line | `#141414` | `#d1e8f9` |
| HTML tag | `#b000c0` | `#e9c063` |
| HTML attribute | `#0000c0` | `#bbcdff` |
| HTML value | `#a01010` | `#a5c35b` |
| doctype | `#005a00` | `#a3baba` |

Ghi chú:

- Header name và path là **hai màu xanh khác nhau**, không phải một. Light: `#000075` so với
  `#0000c0`. Dark: `#d1e8f9` so với `#bbcdff`.
- Trong một theme, path và query key cùng màu. Burp không tách hai thứ này.
- Method trùng màu header name ở theme tối, và trùng màu chữ thường ở theme sáng.

## before/

| File | Nội dung |
|---|---|
| `studio-ui-old.png` | Studio bản cũ, inspector nằm bên trái |
| `poc-render-old.png` | Ảnh render bản cũ |
| `poc-render-old-repaste.png` | Cùng ảnh render cũ, dán lại lần nữa |

## after/

Sinh bởi `ScreenshotVerificationTest`, sao từ `build/`. Chạy `.\build.ps1` để làm mới.

| File | Nội dung |
|---|---|
| `poc-render-dark.png` | Ảnh PoC theme tối |
| `poc-render-light.png` | Ảnh PoC theme sáng |
| `studio-merged-dark.png` | Cả workspace gộp, theme tối, 1400x900 |
| `studio-merged-light.png` | Cả workspace gộp, theme sáng, 1400x900 |
| `palette-dark.png` | Bảng swatch toàn bộ token, theme tối |
| `palette-light.png` | Bảng swatch toàn bộ token, theme sáng |

Hai ảnh `studio-merged-*` là ảnh chụp cả cửa sổ sau khi gộp ảnh và text vào một workspace. Chúng
dùng để bắt lỗi bố cục và lỗi panel không theo theme, những thứ ảnh card đơn lẻ không thấy được.
`checkNoUnthemedGround` trong test quét chính hai ảnh này.

Ảnh `poc-render-*` đã render lại sau khi thanh URL biết xuống dòng: URL dài làm card cao thêm, nên
mọi ảnh cũ có URL dài đều cao hơn bản trước.

## archive/

`render-*-iter*.png` là các bản render trung gian trong lúc refactor. Giữ để thấy tiến trình, không
phải bản hiện tại. `palette-dark-duplicate.png` trùng byte với `build/palette_dark.png`.
`unrelated-claude-skills-ui.png` không liên quan và được đưa vào đây thay vì xoá.

## Đo lại

Chạy từ thư mục gốc dự án.

```
python tools/inventory.py reference/burp-repeater      # kích thước và màu nền từng ảnh
python tools/crop_region.py <ảnh> <out.png> x0 y0 x1 y1 3   # crop 3x
python tools/probe_runs.py <ảnh> x0 x1 y0 y1           # màu theo từng đoạn ngang của một dòng
python tools/probe_pane.py <ảnh> x0 x1 y0 y1           # tự tìm các dòng có chữ, in màu mỗi dòng
python tools/organize_reference.py                     # xếp lại thư mục này
```

`probe_runs.py` cố tình **không** lọc pixel xám. Chữ value trong cả hai theme là màu trung tính; lọc
xám sẽ làm mất nửa dòng header.
