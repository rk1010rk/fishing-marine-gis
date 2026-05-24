from flask import Flask
from flask_migrate import Migrate
from app.models import db
import os

migrate = Migrate()

def create_app():
    app = Flask(__name__)
    
    BASE_DIR = os.path.abspath(os.path.dirname(os.path.dirname(__file__)))
    DB_PATH = os.path.join(BASE_DIR, 'data', 'sqlite', 'fishing.db')
    
    app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{DB_PATH}'
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
    
    db.init_app(app)
    migrate.init_app(app, db)
    
    from app.routes.main import main
    app.register_blueprint(main)
    
    with app.app_context():
        db.create_all()
    
    return app
