/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.web;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.http.OutboundOutcome;
import io.argos.sample.heavy.aop.ServiceCallStats;
import io.argos.sample.heavy.service.DocumentExporter;
import io.argos.sample.heavy.service.FormatCodecs;
import io.argos.sample.heavy.service.InventoryService;
import io.argos.sample.heavy.service.ObjectStorage;
import io.argos.sample.heavy.service.OrderSearch;
import io.argos.sample.heavy.service.ProductSummaries;
import io.argos.sample.heavy.service.ReportMailer;
import io.argos.sample.heavy.service.ReviewService;
import io.argos.sample.heavy.service.SpreadsheetExporter;
import io.argos.sample.heavy.startup.StartupReport;
import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * shop-heavy's own endpoints (besides the whole shop-boot3 catalog): one per heavy library, each
 * doing real work with it. Outgoing calls go to ext-stub only.
 */
@RestController
@RequestMapping("/api/heavy")
public class HeavyController {

  private final StartupReport startup;
  private final ServiceCallStats calls;
  private final ShopJdbc shop;
  private final InventoryService inventory;
  private final ObjectStorage storage;
  private final OrderSearch search;
  private final FormatCodecs formats;
  private final SpreadsheetExporter spreadsheets;
  private final DocumentExporter documents;
  private final ReportMailer mailer;
  private final ProductSummaries products;
  private final ReviewService reviews;

  public HeavyController(
      StartupReport startup,
      ServiceCallStats calls,
      ShopJdbc shop,
      InventoryService inventory,
      ObjectStorage storage,
      OrderSearch search,
      FormatCodecs formats,
      SpreadsheetExporter spreadsheets,
      DocumentExporter documents,
      ReportMailer mailer,
      ProductSummaries products,
      ReviewService reviews) {
    this.startup = startup;
    this.calls = calls;
    this.shop = shop;
    this.inventory = inventory;
    this.storage = storage;
    this.search = search;
    this.formats = formats;
    this.spreadsheets = spreadsheets;
    this.documents = documents;
    this.mailer = mailer;
    this.products = products;
    this.reviews = reviews;
  }

  /** Startup steps with their class counts, and the service call statistics (AOP). */
  @GetMapping("/startup")
  public Map<String, Object> startup() {
    Map<String, Object> result = new LinkedHashMap<>(startup.describe());
    result.put("serviceCalls", calls.snapshot());
    return result;
  }

  /** OpenFeign + LoadBalancer + Resilience4j call to ext-stub; fallback status like /api/pay. */
  @GetMapping("/inventory/{productId}")
  public ResponseEntity<Map<String, Object>> inventory(@PathVariable("productId") long productId) {
    Map<String, Object> result = inventory.inventory(productId);
    return ResponseEntity.status(statusOf(result)).body(result);
  }

  /** AWS SDK S3 GetObject against ext-stub. */
  @GetMapping("/storage/{key}")
  public ResponseEntity<Map<String, Object>> storage(@PathVariable("key") String key) {
    Map<String, Object> result = storage.fetch(key);
    return ResponseEntity.status(statusOf(result)).body(result);
  }

  /** The same with the asynchronous S3 client (Netty), as an async MVC request. */
  @GetMapping("/storage-async/{key}")
  public CompletableFuture<ResponseEntity<Map<String, Object>>> storageAsync(
      @PathVariable("key") String key) {
    return storage
        .fetchAsync(key)
        .thenApply(result -> ResponseEntity.status(statusOf(result)).body(result));
  }

  /** QueryDSL count + page. */
  @GetMapping("/orders/search")
  public Map<String, Object> search(
      @RequestParam(name = "status", defaultValue = "NEW") String status,
      @RequestParam(name = "page", defaultValue = "0") int page) {
    return search.byStatus(status, page);
  }

  /** One order in a Jackson data format: json, xml, yaml, csv, cbor, smile. */
  @GetMapping("/orders/{id}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable("id") long id,
      @RequestParam(name = "format", defaultValue = "xml") String format)
      throws IOException {
    FormatCodecs.Format chosen = FormatCodecs.Format.parse(format);
    Optional<Map<String, Object>> order = shop.findOrder(id);
    if (order.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(chosen.contentType()))
        .body(formats.write(chosen, List.of(order.get())));
  }

  /** One page of orders (the 3 SQL of GET /api/orders?page=) as an Excel workbook (.xlsx). */
  @GetMapping("/orders/report.xlsx")
  public ResponseEntity<byte[]> workbook(@RequestParam(name = "page", defaultValue = "0") int page)
      throws IOException {
    return workbook(page, false);
  }

  /** The same as a legacy Excel 97-2003 workbook (.xls). */
  @GetMapping("/orders/report.xls")
  public ResponseEntity<byte[]> legacyWorkbook(
      @RequestParam(name = "page", defaultValue = "0") int page) throws IOException {
    return workbook(page, true);
  }

  private ResponseEntity<byte[]> workbook(int page, boolean legacy) throws IOException {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) shop.listOrders(page).get("orders");
    String extension = legacy ? "xls" : "xlsx";
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                legacy
                    ? SpreadsheetExporter.LEGACY_CONTENT_TYPE
                    : SpreadsheetExporter.CONTENT_TYPE))
        .header(
            "Content-Disposition", "attachment; filename=\"orders-" + page + "." + extension + "\"")
        .body(spreadsheets.workbook("orders", rows, legacy));
  }

  /** One order as a Word document. */
  @GetMapping("/orders/{id}/report.docx")
  public ResponseEntity<byte[]> document(@PathVariable("id") long id) throws IOException {
    Optional<Map<String, Object>> order = shop.findOrder(id);
    if (order.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(DocumentExporter.CONTENT_TYPE))
        .header("Content-Disposition", "attachment; filename=\"order-" + id + ".docx\"")
        .body(documents.document(order.get()));
  }

  /** Composes (does not send) the report mail of one order. */
  @GetMapping("/orders/{id}/mail")
  public ResponseEntity<Map<String, Object>> mail(@PathVariable("id") long id)
      throws MessagingException, IOException, GeneralSecurityException {
    Optional<Map<String, Object>> order = shop.findOrder(id);
    if (order.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(mailer.compose(order.get()));
  }

  /** Spring HATEOAS model of one order with links (HAL). */
  @GetMapping("/orders/{id}/model")
  public ResponseEntity<EntityModel<Map<String, Object>>> orderModel(@PathVariable("id") long id) {
    return shop.findOrder(id)
        .map(order -> ResponseEntity.ok(model(id, order)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The HATEOAS model of an order; also built at startup (no request needed). */
  public static EntityModel<Map<String, Object>> model(long id, Map<String, Object> order) {
    WebMvcLinkBuilder orderLinks = linkTo(HeavyController.class).slash("orders").slash(id);
    return EntityModel.of(
        order,
        linkTo(methodOn(HeavyController.class).orderModel(id)).withSelfRel(),
        orderLinks.slash("export").withRel("export"),
        orderLinks.slash("mail").withRel("mail"),
        Link.of("/api/orders/" + id + "/items", "items"));
  }

  /** Product summary through the Caffeine cache. */
  @GetMapping("/products/{id}/summary")
  public ResponseEntity<Map<String, Object>> productSummary(@PathVariable("id") long id) {
    return ResponseEntity.of(products.summary(id));
  }

  /** The newest reviews of a product (Spring Data JPA derived query). */
  @GetMapping("/products/{id}/reviews")
  public Map<String, Object> productReviews(@PathVariable("id") long id) {
    return reviews.byProduct(id);
  }

  /** Creates a review (Bean Validation, JPA, Envers revision ADD). */
  @PostMapping("/reviews")
  public ResponseEntity<Map<String, Object>> createReview(
      @Valid @RequestBody ReviewRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(reviews.create(request.productId(), request.rating(), request.comment()));
  }

  /** Changes a review (Envers revision MOD). */
  @PutMapping("/reviews/{id}")
  public ResponseEntity<Map<String, Object>> changeReview(
      @PathVariable("id") long id, @Valid @RequestBody ReviewRequest request) {
    return ResponseEntity.of(reviews.change(id, request.rating(), request.comment()));
  }

  /** Review search: comment text (query by example) and minimum rating (specification). */
  @GetMapping("/reviews")
  public Map<String, Object> searchReviews(
      @RequestParam(name = "q", defaultValue = "") String text,
      @RequestParam(name = "minRating", defaultValue = "1") int minRating,
      @RequestParam(name = "page", defaultValue = "0") int page) {
    return reviews.search(text, minRating, page);
  }

  /** Reads a review (second-level cache after the first read). */
  @GetMapping("/reviews/{id}")
  public ResponseEntity<Map<String, Object>> review(@PathVariable("id") long id) {
    return ResponseEntity.of(reviews.find(id));
  }

  /** Envers revisions of a review. */
  @GetMapping("/reviews/{id}/revisions")
  public Map<String, Object> revisions(@PathVariable("id") long id) {
    return reviews.revisions(id);
  }

  private static int statusOf(Map<String, Object> result) {
    return OutboundOutcome.valueOf(String.valueOf(result.get("outcome"))).appStatus();
  }
}
