/**
 * Bathymetry Raster Renderer
 * depth matrix をリアルタイム Canvas 描画
 */

class BathymetryRenderer {
    constructor(map) {
        this.map = map;
        this.depthGrid = null;
        this.canvasLayer = null;
        this.bounds = null;
        this.resolution = null;
    }
    
    // Depth grid 読み込み
    async load() {
        try {
            const response = await fetch('/static/data/bathymetry.json');
            const data = await response.json();
            
            this.depthGrid = data.grid;
            this.bounds = data.bounds;
            this.resolution = data.resolution;
            
            this.render();
            console.log('✅ Bathymetry Grid 読み込み完了');
        } catch (error) {
            console.error('Bathymetry 読み込み失敗:', error);
        }
    }
    
    // キャンバスレンダリング
    render() {
        if (!this.depthGrid) return;
        
        // Canvas overlay 作成
        const canvas = document.createElement('canvas');
        canvas.width = this.depthGrid[0].length;
        canvas.height = this.depthGrid.length;
        
        const ctx = canvas.getContext('2d');
        const imageData = ctx.createImageData(canvas.width, canvas.height);
        const data = imageData.data;
        
        // Depth raster を RGB に変換
        for (let y = 0; y < this.depthGrid.length; y++) {
            for (let x = 0; x < this.depthGrid[y].length; x++) {
                const depth = this.depthGrid[y][x];
                const idx = (y * canvas.width + x) * 4;
                
                const color = this.depthToRGBA(depth);
                data[idx] = color.r;
                data[idx + 1] = color.g;
                data[idx + 2] = color.b;
                data[idx + 3] = color.a;
            }
        }
        
        ctx.putImageData(imageData, 0, 0);
        
        // Leaflet に overlay
        const bounds = L.latLngBounds(
            [this.bounds.south, this.bounds.west],
            [this.bounds.north, this.bounds.east]
        );
        
        L.imageOverlay(canvas.toDataURL(), bounds, {
            opacity: 0.7,
            zIndex: 200
        }).addTo(this.map);
        
        console.log('✅ Bathymetry raster 描画完了');
    }
    
    // Depth → RGBA 色
    depthToRGBA(depth) {
        let r, g, b, a = 255;
        
        if (depth < 5) {
            // 0-5m: 薄い水色
            r = 155; g = 211; b = 255; a = 200;
        } else if (depth < 10) {
            // 5-10m: 明るい青
            r = 155; g = 211; b = 255; a = 220;
        } else if (depth < 20) {
            // 10-20m: 中程度の青
            r = 95; g = 179; b = 255; a = 230;
        } else if (depth < 50) {
            // 20-50m: やや濃い青
            r = 43; g = 127; b = 255; a = 240;
        } else if (depth < 100) {
            // 50-100m: 濃い青
            r = 0; g = 71; b = 170; a = 250;
        } else {
            // 100m+: 最も濃い青
            r = 0; g = 26; b = 77; a = 255;
        }
        
        return { r, g, b, a };
    }
    
    // Hillshade 計算（将来実装）
    calculateHillshade(depthGrid, azimuth = 315, altitude = 45) {
        // Sobel フィルタで勾配計算
        // 陰影を slope に応じて追加
        console.log('💡 hillshade: 将来実装');
    }
}

// グローバル初期化
let bathymetryRenderer = null;
