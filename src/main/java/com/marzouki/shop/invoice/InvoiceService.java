package com.marzouki.shop.invoice;

import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.common.NotFoundException;
import com.marzouki.shop.order.Order;
import com.marzouki.shop.order.OrderRepository;
import com.marzouki.shop.order.OrderStatus;
import com.marzouki.shop.settings.SettingsService;
import com.marzouki.shop.settings.ShopSettings;
import java.time.Year;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Issues one invoice per order, numbered without gaps, and produces its PDF. */
@Slf4j
@Service
public class InvoiceService {

    private static final int ATTEMPTS = 8;

    private final java.util.concurrent.locks.ReentrantLock issueLock = new java.util.concurrent.locks.ReentrantLock();

    /** A finished PDF and the file name to offer for it. */
    public record Document(String filename, byte[] pdf) {
    }

    private final InvoiceRepository invoices;
    private final OrderRepository orders;
    private final SettingsService settings;
    private final InvoiceRenderer renderer;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    InvoiceService(InvoiceRepository invoices, OrderRepository orders, SettingsService settings, InvoiceRenderer renderer,
                   PlatformTransactionManager transactions) {
        this.invoices = invoices;
        this.orders = orders;
        this.settings = settings;
        this.renderer = renderer;
        this.writes = new TransactionTemplate(transactions);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads.setReadOnly(true);
    }

    /**
     * The invoice for an order, issued now if it does not exist yet. Numbers are the next free one in the
     * year; if two orders race for the same number one transaction rolls back and tries the next, so there are
     * never duplicates and never a gap.
     */
    public Invoice issue(Long orderId) {
        // One at a time inside this process so numbers are rarely contended; the unique key and retry cover several instances.
        issueLock.lock();
        try {
            return issueWithRetry(orderId);
        } finally {
            issueLock.unlock();
        }
    }

    private Invoice issueWithRetry(Long orderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return writes.execute(status -> {
                    var existing = invoices.findByOrderId(orderId);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    Order order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
                    if (order.getStatus() == OrderStatus.CANCELLED) {
                        throw new ConflictException("A cancelled order has no invoice");
                    }
                    int year = Year.now(ZoneOffset.UTC).getValue();
                    ShopSettings shop = settings.get();
                    return invoices.saveAndFlush(new Invoice(orderId, year, invoices.highestSequence(year) + 1, shop.getShopName(),
                            shop.getCompanyAddress(), shop.getVatNumber(), shop.getVatPercent(), shop.getInvoiceNote()));
                });
            } catch (DataIntegrityViolationException | org.springframework.dao.PessimisticLockingFailureException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                pause(attempt);
            }
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Issues the invoice if needed and renders it. The database is only used to read; the PDF is drawn afterwards. */
    public Document document(Long orderId) {
        Invoice invoice = issue(orderId);
        Order order = reads.execute(status -> orders.findWithItemsById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId)));
        byte[] pdf = renderer.render(invoice, order, settings.get().getAccentColor());
        return new Document(invoice.getNumber() + ".pdf", pdf);
    }
}