// 基本地図初期化
const map = L.map('map', {
    center: [35.30, 139.70],
    zoom: 12
});

// OSM ベースレイヤー
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap',
    maxZoom: 19
}).addTo(map);

// レイヤーコントロール
const overlayMaps = {};
let layerControl = L.control.layers({}, overlayMaps, {position: 'topright'}).addTo(map);

// ===== 等深線レイヤー =====
async function loadContours() {
    try {
        const response = await fetch('/static/data/contours.geojson');
        const data = await response.json();
        
        const contourLayer = L.geoJSON(data, {
            style: (feature) => {
                const depth = feature.properties.depth;
                let color = '#1e5eff';
                let weight = 1;
                let opacity = 0.6;
                
                if (depth === 100) {
                    color = '#0037aa';
                    weight = 3;
                    opacity = 1;
                } else if (depth === 50) {
                    weight = 2;
                    opacity = 0.8;
                } else {
                    weight = 0.8;
                    opacity = 0.4;
                }
                
                return { color, weight, opacity };
            },
            onEachFeature: (feature, layer) => {
                layer.bindPopup(`<b>水深: ${feature.properties.depth}m</b>`);
            }
        });
        
        layerControl.addOverlay(contourLayer, "等深線");
        console.log('✅ 等深線読み込み完了');
    } catch (error) {
        console.error('❌ 等深線読み込み失敗:', error);
    }
}

// ===== 底質レイヤー =====
async function loadSeabed() {
    try {
        const response = await fetch('/static/data/seabed_detailed.geojson');
        const data = await response.json();
        
        const seabedLayer = L.geoJSON(data, {
            style: () => ({
                fillColor: '#d4b483',
                fillOpacity: 0.08,
                weight: 0.5,
                color: 'transparent',
                opacity: 0
            }),
            onEachFeature: (feature, layer) => {
                const props = feature.properties;
                layer.bindPopup(`
                    <b>${props.name}</b><br>
                    底質: ${props.bottom_type}<br>
                    水深: ${props.depth}
                `);
            }
        });
        
        layerControl.addOverlay(seabedLayer, "底質情報");
        console.log('✅ 底質読み込み完了');
    } catch (error) {
        console.error('❌ 底質読み込み失敗:', error);
    }
}

// ===== ポイント読み込み =====
async function loadMarkers() {
    try {
        const response = await fetch('/api/points');
        const points = await response.json();
        
        points.forEach(point => {
            const popup = `<b>${point.fish_type}</b><br>${point.lat.toFixed(4)}, ${point.lng.toFixed(4)}`;
            L.circleMarker([point.lat, point.lng], {
                radius: 6,
                fillColor: '#FF6B6B',
                color: 'white',
                weight: 2,
                opacity: 1,
                fillOpacity: 0.8
            }).bindPopup(popup).addTo(map);
        });
        
        console.log(`✅ ${points.length}件のポイント読み込み完了`);
    } catch (error) {
        console.error('❌ ポイント読み込み失敗:', error);
    }
}

// ===== ポイント保存 =====
let selectedLat = null;
let selectedLng = null;

map.on('click', (e) => {
    selectedLat = e.latlng.lat;
    selectedLng = e.latlng.lng;
    document.getElementById('click-info').textContent = `選択中: ${selectedLat.toFixed(4)}, ${selectedLng.toFixed(4)}`;
});

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

// ===== 初期化 =====
(async () => {
    if (typeof waitForDB === 'function') {
        await waitForDB();
    }
    
    document.getElementById('saveBtn').disabled = false;
    
    await loadMarkers();
    await loadContours();
    await loadSeabed();
    
    console.log('✅ 地図初期化完了');
})();

// ===== Bathymetry Renderer 初期化 =====
window.addEventListener('load', async () => {
    try {
        if (typeof BathymetryRenderer !== 'undefined') {
            bathymetryRenderer = new BathymetryRenderer(map);
            await bathymetryRenderer.load();
            console.log('✅ Bathymetry renderer initialized');
        }
    } catch (e) {
        console.error('❌ Bathymetry init failed:', e);
    }
});
