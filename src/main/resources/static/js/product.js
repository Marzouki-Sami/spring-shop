// Variant picker on the product page. The server describes every variant as JSON; this script
// finds the one that matches the chosen options and updates the price, stock and cart form.
(function () {
    var dataElement = document.getElementById('variant-data');
    if (!dataElement) { return; }

    var variants = JSON.parse(dataElement.textContent);
    var groups = Array.prototype.slice.call(document.querySelectorAll('.opt-group'));
    var priceElement = document.getElementById('price');
    var stockElement = document.getElementById('stock');
    var skuElement = document.getElementById('sku');
    var variantInput = document.getElementById('variant-id');
    var quantityInput = document.getElementById('quantity');
    var addButton = document.getElementById('add-to-cart');
    var selected = {};
    // Without an active variant the page has no cart form, so there is nothing to drive.
    if (!addButton || !quantityInput || !variantInput) { return; }

    function text(tag, className, content) {
        var element = document.createElement(tag);
        if (className) { element.className = className; }
        element.textContent = content;
        return element;
    }

    function matching(choice) {
        return variants.filter(function (variant) {
            return Object.keys(choice).every(function (type) {
                return variant.values.indexOf(Number(choice[type])) !== -1;
            });
        });
    }

    function currentVariant() {
        var complete = groups.every(function (group) { return selected[group.dataset.type]; });
        return complete ? matching(selected)[0] : null;
    }

    function showStock(variant) {
        stockElement.textContent = '';
        var badge;
        if (!variant) {
            badge = text('span', 'badge text-bg-secondary', 'Choose your options');
        } else if (variant.stock === 0) {
            badge = text('span', 'badge text-bg-danger', 'Sold out');
        } else if (variant.stock <= 5) {
            badge = text('span', 'badge text-bg-warning', 'Only ' + variant.stock + ' left');
        } else {
            badge = text('span', 'badge text-bg-success', 'In stock');
        }
        stockElement.appendChild(badge);
    }

    function update() {
        var variant = currentVariant();
        groups.forEach(function (group) {
            var type = group.dataset.type;
            var chosenInput = group.querySelector('input:checked');
            group.querySelector('[data-selected-label]').textContent = chosenInput
                ? chosenInput.parentNode.textContent.trim() || chosenInput.value : 'Choose';
            group.querySelectorAll('input').forEach(function (input) {
                var probe = Object.assign({}, selected);
                probe[type] = input.value;
                var possible = matching(probe).some(function (v) { return v.stock > 0; });
                input.parentNode.classList.toggle('opt-unavailable', !possible);
            });
        });
        if (variant) {
            priceElement.textContent = variant.price;
            skuElement.textContent = variant.sku;
            variantInput.value = variant.id;
            quantityInput.max = Math.max(1, Math.min(variant.stock, 20));
            if (Number(quantityInput.value) > Number(quantityInput.max)) { quantityInput.value = quantityInput.max; }
        }
        addButton.disabled = !variant || variant.stock === 0;
        showStock(variant);
    }

    dataElement.parentNode.addEventListener('change', function (event) {
        var input = event.target;
        if (input.type !== 'radio') { return; }
        selected[input.closest('.opt-group').dataset.type] = input.value;
        update();
    });

    var initial = variants.filter(function (v) { return String(v.id) === dataElement.dataset.initial; })[0];
    if (initial) {
        groups.forEach(function (group) {
            group.querySelectorAll('input').forEach(function (input) {
                if (initial.values.indexOf(Number(input.value)) !== -1) {
                    input.checked = true;
                    selected[group.dataset.type] = input.value;
                }
            });
        });
    }
    update();
})();