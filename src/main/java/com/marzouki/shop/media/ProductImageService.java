package com.marzouki.shop.media;

import com.marzouki.shop.catalog.Product;
import com.marzouki.shop.catalog.ProductRepository;
import com.marzouki.shop.common.ConflictException;
import com.marzouki.shop.common.NotFoundException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The pictures of a product: adding, ordering, choosing the preview and removing. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {

    public static final int MAX_IMAGES = 8;

    private final ProductRepository products;
    private final ImageStorage storage;
    private final org.springframework.transaction.support.TransactionTemplate writes;

    public Product productWithImages(Long productId) {
        Product product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        product.getImages().size();
        return product;
    }

    /**
     * Decoding and resizing happen first and the files are written before the transaction opens, so a slow
     * upload never holds a database connection. If the row cannot be saved the files are removed again.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public ProductImage add(Long productId, byte[] data, String altText) {
        ImageProcessor.Processed processed = ImageProcessor.process(data);
        String fileKey = newKey();
        String thumbKey = newKey();
        storage.save(fileKey, processed.large());
        storage.save(thumbKey, processed.thumb());
        try {
            return writes.execute(status -> attach(productId, fileKey, thumbKey, processed, altText));
        } catch (RuntimeException e) {
            storage.delete(fileKey);
            storage.delete(thumbKey);
            throw e;
        }
    }

    private ProductImage attach(Long productId, String fileKey, String thumbKey, ImageProcessor.Processed processed, String altText) {
        Product product = productWithImages(productId);
        if (product.getImages().size() >= MAX_IMAGES) {
            throw new ConflictException("A product can have at most " + MAX_IMAGES + " images");
        }
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setFileKey(fileKey);
        image.setThumbKey(thumbKey);
        image.setWidth(processed.width());
        image.setHeight(processed.height());
        image.setAltText(clean(altText));
        image.setPosition(product.getImages().size());
        product.getImages().add(image);
        return image;
    }
    /** Moves an image one place earlier (negative) or later (positive). */
    @Transactional
    public void move(Long productId, Long imageId, int direction) {
        Product product = productWithImages(productId);
        List<ProductImage> images = product.getImages();
        int index = indexOf(images, imageId);
        int target = Math.max(0, Math.min(images.size() - 1, index + Integer.signum(direction)));
        if (target != index) {
            images.add(target, images.remove(index));
            renumber(images);
        }
    }

    @Transactional
    public void makePreview(Long productId, Long imageId) {
        Product product = productWithImages(productId);
        List<ProductImage> images = product.getImages();
        images.add(0, images.remove(indexOf(images, imageId)));
        renumber(images);
    }

    @Transactional
    public void updateAlt(Long productId, Long imageId, String altText) {
        Product product = productWithImages(productId);
        product.getImages().get(indexOf(product.getImages(), imageId)).setAltText(clean(altText));
    }

    @Transactional
    public void delete(Long productId, Long imageId) {
        Product product = productWithImages(productId);
        List<ProductImage> images = product.getImages();
        ProductImage removed = images.remove(indexOf(images, imageId));
        renumber(images);
        deleteFilesAfterCommit(List.of(removed));
    }

    /** Removes the files of images whose rows are being deleted, once that deletion has committed. */
    public void deleteFilesAfterCommit(Collection<ProductImage> images) {
        List<String> keys = images.stream().flatMap(i -> java.util.stream.Stream.of(i.getFileKey(), i.getThumbKey())).toList();
        if (keys.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    keys.forEach(storage::delete);
                }
            });
        } else {
            keys.forEach(storage::delete);
        }
    }

    private static int indexOf(List<ProductImage> images, Long imageId) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getId().equals(imageId)) {
                return i;
            }
        }
        throw new NotFoundException("Image", imageId);
    }

    private static void renumber(List<ProductImage> images) {
        for (int i = 0; i < images.size(); i++) {
            images.get(i).setPosition(i);
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString().replace("-", "") + ".jpg";
    }

    private static String clean(String altText) {
        if (altText == null || altText.isBlank()) {
            return null;
        }
        String trimmed = altText.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}