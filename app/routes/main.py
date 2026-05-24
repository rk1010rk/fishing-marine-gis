from flask import Blueprint, render_template, request, jsonify
from app.models import db, FishingPoint, GPSTrack
from datetime import datetime, timedelta, timezone
import json

JST = timezone(timedelta(hours=9))

main = Blueprint("main", __name__)

@main.route("/")
def index():
    return render_template("index.html")

@main.route("/api/points", methods=['GET'])
def get_points():
    points = FishingPoint.query.all()
    return jsonify([p.to_dict() for p in points])

@main.route("/api/points", methods=['POST'])
def add_point():
    data = request.json
    print("=== 受信データ ===")
    print(json.dumps(data, indent=2, ensure_ascii=False))
    print("==================")
    
    point = FishingPoint(
        lat=data.get('lat'),
        lng=data.get('lng'),
        fish_type=data.get('fish_type', '不明'),
        memo=data.get('memo', ''),
        lure_name=data.get('lure_name'),
        lure_weight=data.get('lure_weight'),
        lure_color=data.get('lure_color'),
        weather=data.get('weather'),
        water_temp=data.get('water_temp'),
        time_of_day=data.get('time_of_day'),
        tide_name=data.get('tide_name'),
        # Phase 6 追加
        fishing_spot=data.get('fishing_spot'),
        water_depth=data.get('water_depth'),
        bottom_type=data.get('bottom_type'),
        catch_count=data.get('catch_count', 0),
        catch_size=data.get('catch_size')
    )
    db.session.add(point)
    db.session.commit()
    return jsonify(point.to_dict()), 201

@main.route("/api/points/<int:point_id>", methods=['DELETE'])
def delete_point(point_id):
    point = FishingPoint.query.get(point_id)
    if point:
        db.session.delete(point)
        db.session.commit()
        return jsonify({'message': 'deleted'}), 200
    return jsonify({'error': 'not found'}), 404

# GPS航跡関連
@main.route("/api/gps-tracks", methods=['GET'])
def get_gps_tracks():
    """保存済み航跡一覧を取得（light版）"""
    tracks = GPSTrack.query.order_by(GPSTrack.track_date.desc()).all()
    return jsonify([t.to_dict_light() for t in tracks])

@main.route("/api/gps-tracks/<int:track_id>", methods=['GET'])
def get_gps_track(track_id):
    """特定の航跡を取得（全ポイント）"""
    track = GPSTrack.query.get(track_id)
    if track:
        return jsonify(track.to_dict())
    return jsonify({'error': 'not found'}), 404

@main.route("/api/gps-tracks/current", methods=['GET'])
def get_current_track():
    """今日の航跡を取得"""
    today = datetime.now(JST).date()
    track = GPSTrack.query.filter_by(track_date=today).first()
    if track:
        return jsonify(track.to_dict())
    return jsonify({'points': [], 'point_count': 0}), 200

@main.route("/api/gps-tracks/add-point", methods=['POST'])
def add_gps_point():
    """航跡にポイントを追加"""
    data = request.json
    lat = data.get('lat')
    lng = data.get('lng')
    
    today = datetime.now(JST).date()
    track = GPSTrack.query.filter_by(track_date=today).first()
    
    if not track:
        track = GPSTrack(track_date=today)
        db.session.add(track)
    
    track.add_point(lat, lng)
    db.session.commit()
    
    print(f"✅ GPS保存: ({lat}, {lng}) - 累計: {track.point_count}ポイント")
    
    return jsonify(track.to_dict_light()), 201

@main.route("/api/gps-tracks/<int:track_id>", methods=['DELETE'])
def delete_gps_track(track_id):
    """航跡を削除"""
    track = GPSTrack.query.get(track_id)
    if track:
        db.session.delete(track)
        db.session.commit()
        return jsonify({'message': 'deleted'}), 200
    return jsonify({'error': 'not found'}), 404
