import sqlite3 from 'sqlite3';
import path from 'path';
import fs from 'fs';
import { createClient, SupabaseClient } from '@supabase/supabase-js';

// ----------------------------------------------------
// DB Service Interface
// ----------------------------------------------------
export interface DbService {
  init(): Promise<void>;
  deletePairingKeysByDeviceId(deviceId: string): Promise<void>;
  insertPairingKey(key: string, deviceId: string, deviceName: string, expiresAt: string): Promise<void>;
  getPairingKey(key: string): Promise<any>;
  deletePairingKey(key: string): Promise<void>;
  upsertDevice(id: string, name: string, token: string): Promise<void>;
  getDevice(id: string): Promise<any>;
  getDevices(): Promise<any[]>;
  deleteDevice(id: string): Promise<void>;
  updateDeviceStatus(id: string, status: string): Promise<void>;
  updateDeviceTelemetry(id: string, batteryLevel: number, isCharging: boolean, screenStatus: string): Promise<void>;
  getCommandHistory(deviceId: string): Promise<any[]>;
  insertCommand(id: string, deviceId: string, command: string, payload: string | null, status: string): Promise<void>;
  updateCommandStatus(id: string, status: string): Promise<void>;
}

// ----------------------------------------------------
// Supabase Implementation
// ----------------------------------------------------
class SupabaseDbService implements DbService {
  private client: SupabaseClient;

  constructor(url: string, key: string) {
    this.client = createClient(url, key);
  }

  async init(): Promise<void> {
    console.log('[Database] Operating in Supabase Mode.');
  }

  async deletePairingKeysByDeviceId(deviceId: string): Promise<void> {
    const { error } = await this.client
      .from('pairing_keys')
      .delete()
      .eq('device_id', deviceId);
    if (error) throw error;
  }

  async insertPairingKey(key: string, deviceId: string, deviceName: string, expiresAt: string): Promise<void> {
    const { error } = await this.client
      .from('pairing_keys')
      .insert({ key, device_id: deviceId, device_name: deviceName, expires_at: expiresAt });
    if (error) throw error;
  }

  async getPairingKey(key: string): Promise<any> {
    const { data, error } = await this.client
      .from('pairing_keys')
      .select('*')
      .eq('key', key)
      .maybeSingle();
    if (error) throw error;
    if (!data) return null;
    return {
      key: data.key,
      device_id: data.device_id,
      device_name: data.device_name,
      expires_at: data.expires_at
    };
  }

  async deletePairingKey(key: string): Promise<void> {
    const { error } = await this.client
      .from('pairing_keys')
      .delete()
      .eq('key', key);
    if (error) throw error;
  }

  async upsertDevice(id: string, name: string, token: string): Promise<void> {
    const { error } = await this.client
      .from('devices')
      .upsert({ id, name, token, status: 'offline', last_seen: new Date().toISOString() });
    if (error) throw error;
  }

  async getDevice(id: string): Promise<any> {
    const { data, error } = await this.client
      .from('devices')
      .select('id, name, token, status, battery_level, is_charging, screen_status, last_seen')
      .eq('id', id)
      .maybeSingle();
    if (error) throw error;
    return data;
  }

  async getDevices(): Promise<any[]> {
    const { data, error } = await this.client
      .from('devices')
      .select('id, name, status, battery_level, is_charging, screen_status, last_seen');
    if (error) throw error;
    return data || [];
  }

  async deleteDevice(id: string): Promise<void> {
    const { error } = await this.client
      .from('devices')
      .delete()
      .eq('id', id);
    if (error) throw error;
  }

  async updateDeviceStatus(id: string, status: string): Promise<void> {
    const { error } = await this.client
      .from('devices')
      .update({ status, last_seen: new Date().toISOString() })
      .eq('id', id);
    if (error) throw error;
  }

  async updateDeviceTelemetry(id: string, batteryLevel: number, isCharging: boolean, screenStatus: string): Promise<void> {
    const { error } = await this.client
      .from('devices')
      .update({
        battery_level: batteryLevel,
        is_charging: isCharging ? 1 : 0,
        screen_status: screenStatus,
        last_seen: new Date().toISOString()
      })
      .eq('id', id);
    if (error) throw error;
  }

  async getCommandHistory(deviceId: string): Promise<any[]> {
    const { data, error } = await this.client
      .from('command_history')
      .select('id, command, payload, status, created_at, updated_at')
      .eq('device_id', deviceId)
      .order('created_at', { ascending: false })
      .limit(50);
    if (error) throw error;
    return data || [];
  }

  async insertCommand(id: string, deviceId: string, command: string, payload: string | null, status: string): Promise<void> {
    const { error } = await this.client
      .from('command_history')
      .insert({
        id,
        device_id: deviceId,
        command,
        payload,
        status,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      });
    if (error) throw error;
  }

  async updateCommandStatus(id: string, status: string): Promise<void> {
    const { error } = await this.client
      .from('command_history')
      .update({ status, updated_at: new Date().toISOString() })
      .eq('id', id);
    if (error) throw error;
  }
}

// ----------------------------------------------------
// SQLite Implementation
// ----------------------------------------------------
class SQLiteDbService implements DbService {
  private db!: sqlite3.Database;
  private dbPath!: string;

  constructor() {
    const dbDir = path.resolve(__dirname, '../../data');
    if (!fs.existsSync(dbDir)) {
      fs.mkdirSync(dbDir, { recursive: true });
    }
    this.dbPath = path.join(dbDir, 'lockit.db');
  }

  private runQuery(sql: string, params: any[] = []): Promise<{ id?: number | string; changes: number }> {
    return new Promise((resolve, reject) => {
      this.db.run(sql, params, function (err) {
        if (err) reject(err);
        else resolve({ id: this.lastID, changes: this.changes });
      });
    });
  }

  private getQuery<T>(sql: string, params: any[] = []): Promise<T | undefined> {
    return new Promise((resolve, reject) => {
      this.db.get(sql, params, (err, row) => {
        if (err) reject(err);
        else resolve(row as T);
      });
    });
  }

  private allQuery<T>(sql: string, params: any[] = []): Promise<T[]> {
    return new Promise((resolve, reject) => {
      this.db.all(sql, params, (err, rows) => {
        if (err) reject(err);
        else resolve(rows as T[]);
      });
    });
  }

  async init(): Promise<void> {
    console.log('[Database] Operating in SQLite Mode at:', this.dbPath);
    this.db = new sqlite3.Database(this.dbPath);

    await this.runQuery(`
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        token TEXT NOT NULL,
        status TEXT DEFAULT 'offline',
        battery_level INTEGER DEFAULT 50,
        is_charging INTEGER DEFAULT 0,
        screen_status TEXT DEFAULT 'UNKNOWN',
        last_seen DATETIME DEFAULT CURRENT_TIMESTAMP
      )
    `);

    await this.runQuery(`
      CREATE TABLE IF NOT EXISTS pairing_keys (
        key TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        device_name TEXT NOT NULL,
        expires_at DATETIME NOT NULL
      )
    `);

    await this.runQuery(`
      CREATE TABLE IF NOT EXISTS command_history (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        command TEXT NOT NULL,
        payload TEXT,
        status TEXT DEFAULT 'PENDING',
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
      )
    `);
  }

  async deletePairingKeysByDeviceId(deviceId: string): Promise<void> {
    await this.runQuery('DELETE FROM pairing_keys WHERE device_id = ?', [deviceId]);
  }

  async insertPairingKey(key: string, deviceId: string, deviceName: string, expiresAt: string): Promise<void> {
    await this.runQuery(
      'INSERT INTO pairing_keys (key, device_id, device_name, expires_at) VALUES (?, ?, ?, ?)',
      [key, deviceId, deviceName, expiresAt]
    );
  }

  async getPairingKey(key: string): Promise<any> {
    return await this.getQuery('SELECT * FROM pairing_keys WHERE key = ?', [key]);
  }

  async deletePairingKey(key: string): Promise<void> {
    await this.runQuery('DELETE FROM pairing_keys WHERE key = ?', [key]);
  }

  async upsertDevice(id: string, name: string, token: string): Promise<void> {
    await this.runQuery(
      `INSERT INTO devices (id, name, token, status, last_seen)
       VALUES (?, ?, ?, 'offline', CURRENT_TIMESTAMP)
       ON CONFLICT(id) DO UPDATE SET name=excluded.name, token=excluded.token`,
      [id, name, token]
    );
  }

  async getDevice(id: string): Promise<any> {
    return await this.getQuery('SELECT * FROM devices WHERE id = ?', [id]);
  }

  async getDevices(): Promise<any[]> {
    return await this.allQuery('SELECT id, name, status, battery_level, is_charging, screen_status, last_seen FROM devices');
  }

  async deleteDevice(id: string): Promise<void> {
    await this.runQuery('DELETE FROM devices WHERE id = ?', [id]);
  }

  async updateDeviceStatus(id: string, status: string): Promise<void> {
    await this.runQuery(`UPDATE devices SET status = ?, last_seen = CURRENT_TIMESTAMP WHERE id = ?`, [status, id]);
  }

  async updateDeviceTelemetry(id: string, batteryLevel: number, isCharging: boolean, screenStatus: string): Promise<void> {
    await this.runQuery(
      `UPDATE devices
       SET battery_level = ?, is_charging = ?, screen_status = ?, last_seen = CURRENT_TIMESTAMP
       WHERE id = ?`,
      [batteryLevel, isCharging ? 1 : 0, screenStatus, id]
    );
  }

  async getCommandHistory(deviceId: string): Promise<any[]> {
    return await this.allQuery(
      'SELECT id, command, payload, status, created_at, updated_at FROM command_history WHERE device_id = ? ORDER BY created_at DESC LIMIT 50',
      [deviceId]
    );
  }

  async insertCommand(id: string, deviceId: string, command: string, payload: string | null, status: string): Promise<void> {
    await this.runQuery(
      'INSERT INTO command_history (id, device_id, command, payload, status) VALUES (?, ?, ?, ?, ?)',
      [id, deviceId, command, payload, status]
    );
  }

  async updateCommandStatus(id: string, status: string): Promise<void> {
    await this.runQuery(
      `UPDATE command_history
       SET status = ?, updated_at = CURRENT_TIMESTAMP
       WHERE id = ?`,
      [status, id]
    );
  }
}

// ----------------------------------------------------
// Select Database Provider dynamically
// ----------------------------------------------------
const supabaseUrl = process.env.SUPABASE_URL || '';
const supabaseServiceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

export const dbService: DbService = (supabaseUrl && supabaseServiceKey)
  ? new SupabaseDbService(supabaseUrl, supabaseServiceKey)
  : new SQLiteDbService();
