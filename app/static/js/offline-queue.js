const DB_NAME = 'FishingMapDB';
const STORE_NAME = 'pending_points';
const DB_VERSION = 1;

let db = null;
let dbReady = false;

async function initIndexedDB() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
            db = request.result;
            dbReady = true;
            console.log('✅ IndexedDB 初期化完了');
            resolve(db);
        };
        
        request.onupgradeneeded = (event) => {
            const database = event.target.result;
            if (!database.objectStoreNames.contains(STORE_NAME)) {
                database.createObjectStore(STORE_NAME, { keyPath: 'id', autoIncrement: true });
            }
        };
    });
}

async function waitForDB() {
    if (dbReady) return;
    await initIndexedDB();
}

async function savePointOffline(pointData) {
    await waitForDB();
    return new Promise((resolve, reject) => {
        const transaction = db.transaction([STORE_NAME], 'readwrite');
        const store = transaction.objectStore(STORE_NAME);
        const request = store.add({
            ...pointData,
            timestamp: new Date().toISOString(),
            synced: false
        });
        request.onerror = () => reject(request.error);
        request.onsuccess = () => resolve(request.result);
    });
}

async function getPendingPoints() {
    await waitForDB();
    return new Promise((resolve, reject) => {
        const transaction = db.transaction([STORE_NAME], 'readonly');
        const store = transaction.objectStore(STORE_NAME);
        const request = store.getAll();
        request.onerror = () => reject(request.error);
        request.onsuccess = () => resolve(request.result);
    });
}

async function syncOfflineQueue() {
    const pendingPoints = await getPendingPoints();
    if (pendingPoints.length === 0) return;
    
    for (const point of pendingPoints) {
        try {
            const response = await fetch('/api/points', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(point)
            });
            if (response.ok) {
                await removeOfflinePoint(point.id);
            }
        } catch (error) {
            console.error('同期失敗:', error);
        }
    }
}

async function removeOfflinePoint(id) {
    await waitForDB();
    return new Promise((resolve, reject) => {
        const transaction = db.transaction([STORE_NAME], 'readwrite');
        const store = transaction.objectStore(STORE_NAME);
        const request = store.delete(id);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => resolve();
    });
}

async function persistStorage() {
    if (navigator.storage && navigator.storage.persist) {
        try {
            await navigator.storage.persist();
        } catch (error) {
            console.warn('ストレージ永続化失敗:', error);
        }
    }
}

window.addEventListener('online', () => {
    console.log('📡 ネットワーク復帰');
    syncOfflineQueue();
});

(async () => {
    await initIndexedDB();
    await persistStorage();
    console.log('✅ IndexedDB 準備完了');
})();
