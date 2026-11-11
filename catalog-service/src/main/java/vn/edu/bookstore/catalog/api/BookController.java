package vn.edu.bookstore.catalog.api;

import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import vn.edu.bookstore.catalog.api.generated.BooksApi;
import vn.edu.bookstore.catalog.api.model.Book;
import vn.edu.bookstore.catalog.api.model.BookCreateRequest;
import vn.edu.bookstore.catalog.api.model.BookPage;
import vn.edu.bookstore.catalog.api.model.BookUpdateRequest;
import vn.edu.bookstore.catalog.domain.BookEntity;
import vn.edu.bookstore.catalog.service.BookService;
import vn.edu.bookstore.catalog.service.PreconditionFailedException;
import vn.edu.bookstore.catalog.service.PreconditionRequiredException;

/** Hiện thực interface BooksApi sinh từ openapi.yaml (API-first). */
@RestController
public class BookController implements BooksApi {

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    // TH2-4: Phân trang + Sắp xếp + Tìm kiếm
    @Override
    public ResponseEntity<BookPage> listBooks(String q, Integer page, Integer size, String sort) {
        int p = (page != null) ? page : 0;
        int s = (size != null) ? size : 10;
        return ResponseEntity.ok(
            BookMapper.toPage(service.list(q, PageRequest.of(p, s, toSort(sort))))
        );
    }

    // TH2-5: GET /api/v1/books/{isbn} với If-None-Match -> 304
    @Override
    public ResponseEntity<Book> getBook(String isbn, String ifNoneMatch) {
        BookEntity e = service.get(isbn);
        String etag = BookMapper.etag(e);

        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        return ResponseEntity.ok().eTag(etag).body(BookMapper.toApi(e));
    }

    // Tạo sách mới
    @Override
    public ResponseEntity<Book> createBook(BookCreateRequest request) {
        BookEntity e = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{isbn}").buildAndExpand(e.getIsbn()).toUri();
        return ResponseEntity.created(location).eTag(BookMapper.etag(e)).body(BookMapper.toApi(e));
    }

    // TH2-5: PUT /api/v1/books/{isbn} kiểm tra If-Match -> 412/428
    @Override
    public ResponseEntity<Book> updateBook(String isbn, BookUpdateRequest request, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Cần header If-Match chứa ETag đã đọc trước khi cập nhật");
        }

        BookEntity e = service.update(isbn, request, parseVersion(ifMatch));
        return ResponseEntity.ok().eTag(BookMapper.etag(e)).body(BookMapper.toApi(e));
    }

    // Xóa sách
    @Override
    public ResponseEntity<Void> deleteBook(String isbn) {
        service.delete(isbn);
        return ResponseEntity.noContent().build();
    }

    // --- Private Helper Methods ---
    static Sort toSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by("title").and(Sort.by("isbn"));
        }
        String[] parts = sort.split(",");
        String property = switch (parts[0]) {
            case "price" -> "priceAmount";
            case "publishedYear" -> "publishedYear";
            default -> "title";
        };
        Sort.Direction dir = (parts.length > 1 && parts[1].equalsIgnoreCase("desc")) 
                             ? Sort.Direction.DESC 
                             : Sort.Direction.ASC;
        return Sort.by(dir, property).and(Sort.by("isbn"));
    }

    private static boolean matches(String header, String etag) {
        for (String candidate : header.split(",")) {
            String c = candidate.trim();
            if (c.equals("*") || c.replaceFirst("^W/", "").equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static long parseVersion(String ifMatch) {
        String v = ifMatch.trim().replaceFirst("^W/", "").replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ex) {
            throw new PreconditionFailedException("If-Match không hợp lệ: " + ifMatch);
        }
    }
}