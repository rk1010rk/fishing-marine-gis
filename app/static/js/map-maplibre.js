// MapLibre GL JS バージョン
// Leaflet から MapLibre への移行版

const map = new maplibregl.Map({
    container: 'map',
    style: {
        version: 8,
        sources: {
            'osm': {
                type: 'raster',
                tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
                tileSize: 256,
                attribution: '© OpenStreetMap contributors'
            }
        },
        layers: [
            {
                id: 'osm',
                type: 'raster',
                source: 'osm'
            }
        ]
    },
    center: [139.7, 35.45],
    zoom: 12
});

let selectedLat = null;
let selectedLng = null;
let tempMarker = null;

map.on('click', (e) => {
    const { lng, lat } = e.lngLat;
    selectedLat = lat;
    selectedLng = lng;
    document.getElementById('click-info').textContent = 
        `選択中: ${lat.toFixed(4)}, ${lng.toFixed(4)}`;
});

function initCurrentLocation() {
    if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition((position) => {
            const { latitude, longitude } = position.coords;
            map.flyTo({
                center: [longitude, latitude],
                zoom: 13
            });
        });
    }
}

async function loadMarkers() {
    try {
        const response = await fetch('/api/points');
        const points = await response.json();
        points.forEach(point => {
            const popup = new maplibregl.Popup().setHTML(
                `<b>${point.fish_type}</b><br>${point.lat.toFixed(4)}, ${point.lng.toFixed(4)}`
            );
            new maplibregl.Marker({ color: '#FF6B6B' })
                .setLngLat([point.lng, point.lat])
                .setPopup(popup)
                .addTo(map);
        });
        console.log(`✅ ${points.length}件のポイント読み込み完了`);
    } catch (error) {
        console.error('ポイント読み込み失敗:', error);
    }
}

document.getElementById('saveBtn').addEventListener('click', async function() {
    if (!selectedLat) {
        alert('地図をクリックしてポイントを選択してください');
        return;
    }
    
    const pointData = {
        lat: selectedLat,
        lng: selectedLng,
        fish_type: document.getElementById('fishType').value || '不明',
        memo: document.getElementById('memo').value || '',
        lure_name: document.getElementById('lureName').value || null,
        lure_weight: parseFloat(document.getElementById('lureWeight').value) || null,
        lure_color: document.getElementById('lureColor').value || null,
        weather: document.getElementById('weather').value || null,
        water_temp: null,
        time_of_day: document.getElementById('timeOfDay').value || null,
        tide_name: document.getElementById('tideName').value || null,
        fishing_spot: document.getElementById('fishingSpot').value || null,
        water_depth: parseFloat(document.getElementById('waterDepth').value) || null,
        bottom_type: document.getElementById('bottomType').value || null,
        catch_count: parseInt(document.getElementById('catchCount').value) || 0,
        catch_size: document.getElementById('catchSize').value || null
    };
    
    try {
        const response = await fetch('/api/points', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(pointData)
        });
        
        if (response.ok) {
            alert('✅ 保存しました');
            location.reload();
        }
    } catch (error) {
        if (typeof savePointOffline === 'function') {
            await savePointOffline(pointData);
            alert('⚠️ オフライン保存');
        }
    }
});

document.getElementById('cancelBtn').addEventListener('click', function() {
    selectedLat = null;
    selectedLng = null;
    document.getElementById('click-info').textContent = '地図をクリックしてポイント追加';
});

function switchTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    event.target.classList.add('active');
    document.getElementById(tab + '-tab').classList.add('active');
}

(async () => {
    if (typeof waitForDB === 'function') {
        await waitForDB();
    }
    
    document.getElementById('saveBtn').disabled = false;
    
    map.on('load', async () => {
        initCurrentLocation();
        await loadMarkers();
        console.log('✅ MapLibre 初期化完了');
    });
})();
