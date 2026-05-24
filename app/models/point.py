from flask_sqlalchemy import SQLAlchemy
from datetime import datetime, timedelta, timezone
import json

db = SQLAlchemy()

# 日本時間（JST = UTC+9）
JST = timezone(timedelta(hours=9))

class FishingPoint(db.Model):
    __tablename__ = 'fishing_points'
    
    # 基本情報
    id = db.Column(db.Integer, primary_key=True)
    lat = db.Column(db.Float, nullable=False)
    lng = db.Column(db.Float, nullable=False)
    fish_type = db.Column(db.String(100))
    memo = db.Column(db.Text)
    created_at = db.Column(
        db.DateTime, 
        default=lambda: datetime.now(JST),
        index=True
    )
    
    # ルアー系
    lure_name = db.Column(db.String(100), nullable=True)
    lure_weight = db.Column(db.Float, nullable=True)
    lure_color = db.Column(db.String(50), nullable=True)
    
    # 環境系
    weather = db.Column(db.String(50), nullable=True)
    water_temp = db.Column(db.Float, nullable=True)
    time_of_day = db.Column(db.String(20), nullable=True)
    tide_name = db.Column(db.String(20), nullable=True)
    
    # Phase 6 追加
    fishing_spot = db.Column(db.String(100), nullable=True)  # 船宿/釣り場
    water_depth = db.Column(db.Float, nullable=True)  # 水深（m）
    bottom_type = db.Column(db.String(50), nullable=True)  # 底質（砂/泥/岩盤）
    catch_count = db.Column(db.Integer, default=0)  # 釣果数
    catch_size = db.Column(db.String(100), nullable=True)  # サイズ（例：35cm）
    
    def to_dict(self):
        return {
            'id': self.id,
            'lat': self.lat,
            'lng': self.lng,
            'fish_type': self.fish_type,
            'memo': self.memo,
            'created_at': self.created_at.isoformat(),
            'lure_name': self.lure_name,
            'lure_weight': self.lure_weight,
            'lure_color': self.lure_color,
            'weather': self.weather,
            'water_temp': self.water_temp,
            'time_of_day': self.time_of_day,
            'tide_name': self.tide_name,
            'fishing_spot': self.fishing_spot,
            'water_depth': self.water_depth,
            'bottom_type': self.bottom_type,
            'catch_count': self.catch_count,
            'catch_size': self.catch_size,
        }


class GPSTrack(db.Model):
    """
    GPS航跡記録
    
    NOTE:
    現段階は JSON 保存で実装速度優先。
    将来的には以下への正規化を予定：
    - GPSPoint テーブル（座標の正規化）
    - FishingSession テーブル（釣行セッション管理：日跨ぎ対応）
    """
    __tablename__ = 'gps_tracks'
    
    id = db.Column(db.Integer, primary_key=True)
    track_date = db.Column(db.Date, default=lambda: datetime.now(JST).date(), index=True)
    
    points = db.Column(db.Text, nullable=True)
    point_count = db.Column(db.Integer, default=0)
    last_lat = db.Column(db.Float, nullable=True)
    last_lng = db.Column(db.Float, nullable=True)
    
    created_at = db.Column(
        db.DateTime,
        default=lambda: datetime.now(JST),
        index=True
    )
    updated_at = db.Column(
        db.DateTime,
        default=lambda: datetime.now(JST),
        onupdate=lambda: datetime.now(JST)
    )
    
    def add_point(self, lat, lng):
        if self.points:
            track_list = json.loads(self.points)
        else:
            track_list = []
        
        track_list.append({
            'lat': lat,
            'lng': lng,
            'timestamp': datetime.now(JST).isoformat()
        })
        
        self.points = json.dumps(track_list)
        self.point_count = len(track_list)
        self.last_lat = lat
        self.last_lng = lng
    
    def get_points(self):
        if self.points:
            return json.loads(self.points)
        return []
    
    def to_dict(self):
        return {
            'id': self.id,
            'track_date': self.track_date.isoformat(),
            'points': self.get_points(),
            'point_count': self.point_count,
            'last_lat': self.last_lat,
            'last_lng': self.last_lng,
            'created_at': self.created_at.isoformat(),
            'updated_at': self.updated_at.isoformat()
        }
    
    def to_dict_light(self):
        return {
            'id': self.id,
            'track_date': self.track_date.isoformat(),
            'point_count': self.point_count,
            'last_lat': self.last_lat,
            'last_lng': self.last_lng,
            'created_at': self.created_at.isoformat()
        }
