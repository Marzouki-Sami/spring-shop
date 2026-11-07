// Address entry: choosing between saved addresses and a new one, and (when the map is enabled)
// searching for an address, using the current location, or picking a spot on a map. Every route fills
// the same ordinary form fields, which stay editable, so typing the address by hand always works.
(function () {
    var newAddress = document.getElementById('new-address');
    document.querySelectorAll('input[name="addressId"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
            if (radio.checked && newAddress) { newAddress.classList.toggle('d-none', radio.value !== ''); }
        });
    });

    var picker = document.querySelector('.address-picker');
    if (!picker || picker.dataset.geo !== 'true' || !window.L) { return; }

    var searchInput = document.getElementById('geo-search');
    var results = document.getElementById('geo-results');
    var locateButton = document.getElementById('geo-locate');
    var mapToggle = document.getElementById('geo-map-toggle');
    var panel = document.getElementById('geo-map-panel');
    var mapElement = document.getElementById('geo-map');
    var map = null;
    var marker = null;
    var searchTimer = null;
    var searchSequence = 0;

    function field(name) { return picker.querySelector('[name="' + name + '"]'); }
    function toast(kind, message) { if (window.shopToast) { window.shopToast(kind, message); } }

    function setValue(name, value) {
        var input = field(name);
        if (input && value !== undefined && value !== null) {
            input.value = value;
            input.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function getJson(url) {
        return fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' }).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (body) {
                if (!response.ok) {
                    var error = new Error(body.error || 'The request failed');
                    error.status = response.status;
                    throw error;
                }
                return body;
            });
        });
    }

    function fill(place, keepLabel) {
        setValue('address', place.street);
        setValue('postalCode', place.postalCode);
        setValue('city', place.city);
        setValue('country', place.country);
        setValue('latitude', Number(place.lat).toFixed(6));
        setValue('longitude', Number(place.lon).toFixed(6));
        if (!keepLabel) { searchInput.value = place.label; }
        showMarker(place.lat, place.lon);
    }

    function position() {
        var lat = parseFloat(field('latitude').value);
        var lon = parseFloat(field('longitude').value);
        return isNaN(lat) || isNaN(lon) ? null : [lat, lon];
    }

    // --- search ---------------------------------------------------------------------------
    function closeResults() {
        results.hidden = true;
    }

    function showResults(places) {
        results.textContent = '';
        if (!places.length) {
            var none = document.createElement('li');
            none.className = 'geo-empty';
            none.textContent = 'No matching address found';
            results.append(none);
        }
        places.forEach(function (place) {
            var item = document.createElement('li');
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = place.label;
            button.addEventListener('click', function () {
                fill(place);
                closeResults();
                openMap(true);
            });
            item.append(button);
            results.append(item);
        });
        results.hidden = false;
    }

    searchInput.addEventListener('input', function () {
        clearTimeout(searchTimer);
        var query = searchInput.value.trim();
        if (query.length < 3) { closeResults(); return; }
        searchTimer = setTimeout(function () {
            var sequence = ++searchSequence;
            getJson('/geo/search?q=' + encodeURIComponent(query)).then(function (places) {
                if (sequence === searchSequence) { showResults(places); }
            }).catch(function (error) {
                if (sequence === searchSequence) { closeResults(); toast('warning', error.message); }
            });
        }, 450);
    });
    searchInput.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { closeResults(); }
        // Enter in the search box must search, not submit the surrounding checkout or address form.
        if (event.key === 'Enter') { event.preventDefault(); }
    });
    document.addEventListener('click', function (event) {
        if (!results.contains(event.target) && event.target !== searchInput) { closeResults(); }
    });

    // --- map ------------------------------------------------------------------------------
    var pinIcon = window.L.divIcon({ className: 'geo-pin', html: '<i class="bi bi-geo-alt-fill"></i>',
        iconSize: [32, 32], iconAnchor: [16, 30] });

    function ensureMap() {
        if (map) { return; }
        var start = position();
        map = window.L.map(mapElement, { center: start || [46.6, 2.4], zoom: start ? 16 : 5 });
        window.L.tileLayer(picker.dataset.tileUrl, { maxZoom: 19, attribution: picker.dataset.attribution }).addTo(map);
        map.on('click', function (event) { choose(event.latlng.lat, event.latlng.lng); });
        if (start) { showMarker(start[0], start[1]); }
    }

    function showMarker(lat, lon) {
        if (!map) { return; }
        if (!marker) {
            marker = window.L.marker([lat, lon], { icon: pinIcon, draggable: true, keyboard: true, title: 'Delivery spot' }).addTo(map);
            marker.on('dragend', function () { var at = marker.getLatLng(); choose(at.lat, at.lng); });
        } else {
            marker.setLatLng([lat, lon]);
        }
        map.setView([lat, lon], Math.max(map.getZoom(), 16));
    }

    function openMap(open) {
        panel.hidden = !open;
        mapToggle.setAttribute('aria-expanded', String(open));
        if (open) {
            ensureMap();
            setTimeout(function () { map.invalidateSize(); var at = position(); if (at) { showMarker(at[0], at[1]); } }, 60);
        }
    }

    mapToggle.addEventListener('click', function () { openMap(panel.hidden); });

    // A spot was chosen on the map or by the browser: look up its address and fill the form.
    function choose(lat, lon) {
        setValue('latitude', lat.toFixed(6));
        setValue('longitude', lon.toFixed(6));
        getJson('/geo/reverse?lat=' + lat + '&lon=' + lon).then(function (place) {
            place.lat = lat;
            place.lon = lon;
            fill(place);
        }).catch(function (error) {
            showMarker(lat, lon);
            toast(error.status === 404 ? 'info' : 'warning',
                error.status === 404 ? 'No address was found at that spot. Please fill in the address below.' : error.message);
        });
    }

    // --- current location -----------------------------------------------------------------
    if (!navigator.geolocation) {
        locateButton.hidden = true;
    }
    locateButton.addEventListener('click', function () {
        locateButton.disabled = true;
        navigator.geolocation.getCurrentPosition(function (coords) {
            locateButton.disabled = false;
            openMap(true);
            choose(coords.coords.latitude, coords.coords.longitude);
        }, function (error) {
            locateButton.disabled = false;
            toast('warning', error.code === 1
                ? 'Location access is blocked. Allow it in your browser, or search for the address instead.'
                : 'We could not find your location. Try searching for the address instead.');
        }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 });
    });
})();