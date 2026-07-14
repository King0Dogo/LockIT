import React, { useState, useEffect, useRef } from 'react';
import { io, Socket } from 'socket.io-client';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:4000';

interface Device {
  id: string;
  name: string;
  status: 'online' | 'offline';
  battery_level: number;
  is_charging: number; // 0 or 1
  screen_status: 'LOCKED' | 'UNLOCKED' | 'UNKNOWN';
  last_seen: string;
}

interface CommandLog {
  id: string;
  command: string;
  payload: string | null;
  status: 'PENDING' | 'EXECUTED' | 'FAILED';
  created_at: string;
  updated_at: string;
}

export default function App() {
  const [passcode, setPasscode] = useState('');
  const [token, setToken] = useState<string | null>(localStorage.getItem('admin_token'));
  const [loginError, setLoginError] = useState('');
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [history, setHistory] = useState<CommandLog[]>([]);
  const [pairingKey, setPairingKey] = useState('');
  const [pairingMsg, setPairingMsg] = useState({ text: '', isError: false });
  const [notificationText, setNotificationText] = useState('');
  const [isSendingCmd, setIsSendingCmd] = useState<string | null>(null);

  const socketRef = useRef<Socket | null>(null);

  // Authenticate Admin
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginError('');
    try {
      const res = await fetch(`${API_URL}/api/admin/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ passcode })
      });
      const data = await res.json();
      if (res.ok && data.token) {
        localStorage.setItem('admin_token', data.token);
        setToken(data.token);
      } else {
        setLoginError(data.error || 'Authentication failed');
      }
    } catch (err) {
      setLoginError('Could not reach backend server.');
    }
  };

  // Log Out
  const handleLogout = () => {
    localStorage.removeItem('admin_token');
    setToken(null);
    setDevices([]);
    if (socketRef.current) {
      socketRef.current.disconnect();
    }
  };

  // Fetch all devices
  const fetchDevices = async () => {
    if (!token) return;
    try {
      const res = await fetch(`${API_URL}/api/admin/devices`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (res.ok) {
        const data = await res.json();
        setDevices(data);
        if (data.length > 0 && !selectedDeviceId) {
          setSelectedDeviceId(data[0].id);
        }
      } else if (res.status === 401) {
        handleLogout();
      }
    } catch (err) {
      console.error('Error fetching devices:', err);
    }
  };

  // Fetch logs for active device
  const fetchHistory = async (deviceId: string) => {
    if (!token) return;
    try {
      const res = await fetch(`${API_URL}/api/admin/history/${deviceId}`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (res.ok) {
        const data = await res.json();
        setHistory(data);
      }
    } catch (err) {
      console.error('Error fetching history:', err);
    }
  };

  // Send remote command
  const sendCommand = async (deviceId: string, command: string, payload?: any) => {
    if (!token) return;
    setIsSendingCmd(command);
    try {
      const res = await fetch(`${API_URL}/api/admin/command`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify({ deviceId, command, payload })
      });
      const data = await res.json();
      if (!res.ok) {
        alert(data.error || 'Failed to dispatch command.');
      } else {
        // Optimistic / instant refresh of logs
        fetchHistory(deviceId);
      }
    } catch (err) {
      alert('Error communicating with command router.');
    } finally {
      setIsSendingCmd(null);
    }
  };

  // Handle device unpairing
  const handleUnpair = async (deviceId: string) => {
    if (!token) return;
    if (!confirm('Are you sure you want to unpair this device? Connection details will be deleted.')) return;
    try {
      const res = await fetch(`${API_URL}/api/admin/devices/${deviceId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` }
      });
      if (res.ok) {
        setDevices(prev => prev.filter(d => d.id !== deviceId));
        if (selectedDeviceId === deviceId) {
          setSelectedDeviceId(null);
        }
      }
    } catch (err) {
      alert('Failed to unpair device.');
    }
  };

  // Handle new pairing submission
  const handlePairSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setPairingMsg({ text: '', isError: false });
    if (!pairingKey || pairingKey.trim().length !== 8) {
      setPairingMsg({ text: 'Please enter a valid 8-character key.', isError: true });
      return;
    }
    try {
      const res = await fetch(`${API_URL}/api/admin/pair`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify({ pairingKey })
      });
      const data = await res.json();
      if (res.ok) {
        setPairingMsg({ text: `Paired successfully with ${data.deviceName}!`, isError: false });
        setPairingKey('');
        fetchDevices();
        if (data.deviceId) setSelectedDeviceId(data.deviceId);
      } else {
        setPairingMsg({ text: data.error || 'Failed to pair device.', isError: true });
      }
    } catch (err) {
      setPairingMsg({ text: 'Error connecting to pair service.', isError: true });
    }
  };

  // Sync logs when active device changes
  useEffect(() => {
    if (selectedDeviceId) {
      fetchHistory(selectedDeviceId);
    } else {
      setHistory([]);
    }
  }, [selectedDeviceId]);

  // Initial load and WebSocket listener setup
  useEffect(() => {
    if (!token) return;
    fetchDevices();

    // Setup Socket.io admin connection
    const socket = io(API_URL, {
      auth: {
        role: 'admin',
        token: token
      }
    });

    socketRef.current = socket;

    socket.on('connect', () => {
      console.log('WS: Connected to relay server.');
    });

    socket.on('devices_changed', () => {
      fetchDevices();
    });

    socket.on('history_changed', (data: { deviceId: string }) => {
      if (selectedDeviceId === data.deviceId || !selectedDeviceId) {
        fetchHistory(data.deviceId);
      }
    });

    socket.on('device_status_change', (data: { deviceId: string; status: string }) => {
      setDevices(prev =>
        prev.map(d => (d.id === data.deviceId ? { ...d, status: data.status as any } : d))
      );
    });

    socket.on('connect_error', (err) => {
      console.error('WS Error:', err.message);
      if (err.message.includes('Authentication error')) {
        handleLogout();
      }
    });

    return () => {
      socket.disconnect();
    };
  }, [token]);

  // Formatting date strings
  const formatDate = (isoString: string) => {
    try {
      const d = new Date(isoString);
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
      return isoString;
    }
  };

  // Render Login Panel
  if (!token) {
    return (
      <div className="login-screen">
        <div className="glass-panel login-card">
          <div className="logo-section">
            <div className="logo-glow pulse-indicator">🔒</div>
            <h2>LockIT</h2>
            <p>Admin Portal Access</p>
          </div>
          <form onSubmit={handleLogin}>
            <div className="input-group">
              <label>Security Key</label>
              <input
                type="password"
                placeholder="••••••••••••"
                value={passcode}
                onChange={e => setPasscode(e.target.value)}
                required
              />
            </div>
            {loginError && <p className="error-text">{loginError}</p>}
            <button type="submit" className="glow-btn">Verify Access</button>
          </form>
        </div>
        <style>{`
          .login-screen {
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 90vh;
            padding: 20px;
          }
          .login-card {
            width: 100%;
            max-width: 400px;
            padding: 40px 30px;
            text-align: center;
          }
          .logo-section {
            margin-bottom: 30px;
          }
          .logo-glow {
            font-size: 3rem;
            margin-bottom: 15px;
            display: inline-block;
            color: var(--accent-blue);
          }
          h2 {
            font-size: 1.8rem;
            margin-bottom: 5px;
            color: var(--text-main);
          }
          p {
            font-size: 0.9rem;
            color: var(--text-muted);
          }
          .input-group {
            text-align: left;
            margin-bottom: 20px;
          }
          .input-group label {
            display: block;
            font-size: 0.8rem;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--text-muted);
            margin-bottom: 8px;
          }
          .input-group input {
            width: 100%;
            padding: 12px 16px;
            background: rgba(0, 0, 0, 0.4);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            color: var(--text-main);
            font-family: inherit;
            outline: none;
            transition: border-color 0.2s;
          }
          .input-group input:focus {
            border-color: var(--accent-blue);
          }
          .error-text {
            color: var(--accent-red);
            font-size: 0.85rem;
            margin-bottom: 15px;
            text-align: left;
          }
          .glow-btn {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, var(--accent-blue), var(--accent-purple));
            border: none;
            border-radius: 8px;
            color: #fff;
            font-family: var(--font-heading);
            font-weight: 600;
            cursor: pointer;
            box-shadow: 0 0 15px var(--accent-blue-glow);
            transition: all 0.2s;
          }
          .glow-btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 0 25px var(--accent-blue-glow);
          }
        `}</style>
      </div>
    );
  }

  // Active Selected Device Details
  const activeDevice = devices.find(d => d.id === selectedDeviceId);

  return (
    <div className="app-container">
      {/* Top Navbar */}
      <header className="glass-panel main-header">
        <div className="header-brand">
          <div className="dot pulse-indicator active-dot"></div>
          <h1>LockIT</h1>
          <span className="server-tag">Relay Active</span>
        </div>
        <button className="logout-btn" onClick={handleLogout}>Log Out</button>
      </header>

      <div className="dashboard-grid">
        {/* Left Side: Pairing and Devices List */}
        <div className="sidebar-column">
          {/* Pairing Widget */}
          <div className="glass-panel sidebar-card">
            <h3>Pair New Device</h3>
            <p className="card-desc">Enter the 8-character key generated on the Android application.</p>
            <form onSubmit={handlePairSubmit} className="pair-form">
              <input
                type="text"
                maxLength={8}
                placeholder="ABCDEF12"
                value={pairingKey}
                onChange={e => setPairingKey(e.target.value.toUpperCase())}
                required
              />
              <button type="submit">Pair</button>
            </form>
            {pairingMsg.text && (
              <p className={`msg-text ${pairingMsg.isError ? 'err' : 'ok'}`}>
                {pairingMsg.text}
              </p>
            )}
          </div>

          {/* Connected Devices List */}
          <div className="glass-panel sidebar-card devices-list-card">
            <div className="card-header">
              <h3>Devices ({devices.length})</h3>
              <button className="refresh-btn" onClick={fetchDevices}>🔄</button>
            </div>
            <div className="devices-list">
              {devices.length === 0 ? (
                <div className="empty-state">No devices paired.</div>
              ) : (
                devices.map(device => (
                  <div
                    key={device.id}
                    className={`device-row-item ${selectedDeviceId === device.id ? 'active' : ''}`}
                    onClick={() => setSelectedDeviceId(device.id)}
                  >
                    <div className="device-row-header">
                      <span className="device-row-name">{device.name}</span>
                      <span className={`status-pill ${device.status}`}>
                        {device.status}
                      </span>
                    </div>
                    <div className="device-row-stats">
                      <span>🔋 {device.battery_level}%</span>
                      <span>📱 {device.screen_status}</span>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        {/* Right Side: Active Device Telemetry & Control Panel */}
        <div className="main-content-column">
          {activeDevice ? (
            <>
              {/* Telemetry Panel */}
              <div className="glass-panel control-card main-telemetry-card">
                <div className="card-header-actions">
                  <div>
                    <h2>{activeDevice.name}</h2>
                    <p className="device-id-label">ID: {activeDevice.id}</p>
                  </div>
                  <button className="unpair-link-btn" onClick={() => handleUnpair(activeDevice.id)}>
                    Unpair Device
                  </button>
                </div>

                <div className="telemetry-widgets">
                  {/* Battery Widget */}
                  <div className="telemetry-box">
                    <span className="telemetry-icon">🔋</span>
                    <div className="telemetry-details">
                      <span className="telemetry-title">Battery Status</span>
                      <div className="battery-display">
                        <div className="battery-bar-container">
                          <div
                            className={`battery-fill ${
                              activeDevice.battery_level > 50
                                ? 'good'
                                : activeDevice.battery_level > 20
                                ? 'mid'
                                : 'low'
                            }`}
                            style={{ width: `${activeDevice.battery_level}%` }}
                          ></div>
                          <span className="battery-percent-label">{activeDevice.battery_level}%</span>
                        </div>
                        {activeDevice.is_charging === 1 && (
                          <span className="charging-badge">Charging</span>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Screen State Widget */}
                  <div className="telemetry-box">
                    <span className="telemetry-icon">
                      {activeDevice.screen_status === 'LOCKED' ? '🔒' : '🔓'}
                    </span>
                    <div className="telemetry-details">
                      <span className="telemetry-title">Screen Status</span>
                      <span className={`screen-badge ${activeDevice.screen_status.toLowerCase()}`}>
                        {activeDevice.screen_status}
                      </span>
                    </div>
                  </div>

                  {/* Connection Ping */}
                  <div className="telemetry-box">
                    <span className="telemetry-icon">📡</span>
                    <div className="telemetry-details">
                      <span className="telemetry-title">Network Link</span>
                      <span className={`network-badge ${activeDevice.status}`}>
                        {activeDevice.status === 'online' ? 'Connected' : 'Offline'}
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Actions Grid */}
              <div className="actions-section">
                <div className="glass-panel control-card actions-panel">
                  <h3>Remote Operations</h3>
                  <p className="section-desc">Instantly dispatch secure execution calls to the client.</p>

                  <div className="buttons-grid">
                    {/* LOCK SCREEN */}
                    <button
                      className="action-trigger-btn lock-btn"
                      disabled={activeDevice.status === 'offline' || isSendingCmd !== null}
                      onClick={() => sendCommand(activeDevice.id, 'lock')}
                    >
                      <span className="btn-icon">🔒</span>
                      <span className="btn-text">Lock Device Now</span>
                    </button>

                    {/* RING PHONE */}
                    <button
                      className="action-trigger-btn ring-btn"
                      disabled={activeDevice.status === 'offline' || isSendingCmd !== null}
                      onClick={() => sendCommand(activeDevice.id, 'ring')}
                    >
                      <span className="btn-icon">🔊</span>
                      <span className="btn-text">Trigger Sound Alert</span>
                    </button>
                  </div>

                  <div className="notify-input-panel">
                    <h4>Send Remote Alert Notification</h4>
                    <div className="notify-field">
                      <input
                        type="text"
                        placeholder="Alert Message (e.g. Please check your phone!)"
                        value={notificationText}
                        onChange={e => setNotificationText(e.target.value)}
                        disabled={activeDevice.status === 'offline'}
                      />
                      <button
                        disabled={activeDevice.status === 'offline' || !notificationText.trim() || isSendingCmd !== null}
                        onClick={() => {
                          sendCommand(activeDevice.id, 'notify', { message: notificationText });
                          setNotificationText('');
                        }}
                      >
                        Send Alert
                      </button>
                    </div>
                  </div>
                </div>

                {/* Operations History Log */}
                <div className="glass-panel control-card history-panel">
                  <h3>Operations Ledger</h3>
                  <div className="logs-terminal">
                    {history.length === 0 ? (
                      <div className="terminal-empty">No transaction history.</div>
                    ) : (
                      history.map(log => (
                        <div key={log.id} className="terminal-row">
                          <span className="time">{formatDate(log.created_at)}</span>
                          <span className="cmd">&gt; {log.command.toUpperCase()}</span>
                          {log.payload && (
                            <span className="payload">
                              ({JSON.parse(log.payload).message || JSON.parse(log.payload).toString()})
                            </span>
                          )}
                          <span className={`status ${log.status.toLowerCase()}`}>
                            {log.status}
                          </span>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </>
          ) : (
            <div className="glass-panel empty-active-view">
              <span className="large-icon">📱</span>
              <h3>No Active Device Selected</h3>
              <p>Select a paired device from the sidebar or pair a new device to get started.</p>
            </div>
          )}
        </div>
      </div>

      <style>{`
        .main-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 15px 30px;
          margin-bottom: 30px;
        }
        .header-brand {
          display: flex;
          align-items: center;
          gap: 12px;
        }
        .dot {
          width: 10px;
          height: 10px;
          border-radius: 50%;
        }
        .active-dot {
          background-color: var(--accent-green);
          box-shadow: 0 0 10px var(--accent-green-glow);
        }
        .main-header h1 {
          font-size: 1.4rem;
          font-weight: 700;
        }
        .server-tag {
          font-size: 0.75rem;
          background: rgba(59, 130, 246, 0.15);
          color: var(--accent-blue);
          border: 1px solid rgba(59, 130, 246, 0.3);
          padding: 3px 8px;
          border-radius: 12px;
          font-weight: 600;
          text-transform: uppercase;
        }
        .logout-btn {
          background: transparent;
          border: 1px solid var(--border-color);
          color: var(--text-muted);
          padding: 8px 16px;
          border-radius: 8px;
          cursor: pointer;
          font-family: inherit;
          transition: all 0.2s;
        }
        .logout-btn:hover {
          color: var(--text-main);
          border-color: var(--accent-red);
          background: rgba(239, 68, 68, 0.05);
        }

        /* Dashboard Grid Layout */
        .dashboard-grid {
          display: grid;
          grid-template-columns: 350px 1fr;
          gap: 25px;
          align-items: start;
        }

        .sidebar-column {
          display: flex;
          flex-direction: column;
          gap: 25px;
        }
        .sidebar-card {
          padding: 24px;
        }
        .sidebar-card h3 {
          font-size: 1.15rem;
          margin-bottom: 8px;
          color: var(--text-main);
        }
        .card-desc {
          font-size: 0.85rem;
          color: var(--text-muted);
          line-height: 1.4;
          margin-bottom: 16px;
        }
        .pair-form {
          display: flex;
          gap: 10px;
        }
        .pair-form input {
          flex: 1;
          padding: 10px 14px;
          background: rgba(0, 0, 0, 0.4);
          border: 1px solid var(--border-color);
          border-radius: 8px;
          color: var(--text-main);
          font-weight: 600;
          letter-spacing: 0.1em;
          text-align: center;
          outline: none;
          transition: border-color 0.2s;
        }
        .pair-form input:focus {
          border-color: var(--accent-blue);
        }
        .pair-form button {
          padding: 10px 18px;
          background: var(--accent-blue);
          border: none;
          border-radius: 8px;
          color: white;
          cursor: pointer;
          font-weight: 600;
          transition: background 0.2s;
        }
        .pair-form button:hover {
          background: #2563eb;
        }
        .msg-text {
          margin-top: 12px;
          font-size: 0.85rem;
        }
        .msg-text.err { color: var(--accent-red); }
        .msg-text.ok { color: var(--accent-green); }

        .devices-list-card {
          flex: 1;
        }
        .card-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 16px;
        }
        .refresh-btn {
          background: none;
          border: none;
          cursor: pointer;
          font-size: 1.1rem;
        }
        .devices-list {
          display: flex;
          flex-direction: column;
          gap: 12px;
          max-height: 400px;
          overflow-y: auto;
        }
        .empty-state {
          text-align: center;
          padding: 30px;
          color: var(--text-muted);
          font-size: 0.9rem;
        }
        .device-row-item {
          padding: 14px;
          background: rgba(255, 255, 255, 0.02);
          border: 1px solid var(--border-color);
          border-radius: 10px;
          cursor: pointer;
          transition: all 0.2s;
        }
        .device-row-item:hover {
          background: rgba(255, 255, 255, 0.05);
          border-color: rgba(255, 255, 255, 0.15);
        }
        .device-row-item.active {
          background: rgba(59, 130, 246, 0.06);
          border-color: var(--accent-blue);
          box-shadow: 0 0 10px rgba(59, 130, 246, 0.1);
        }
        .device-row-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 8px;
        }
        .device-row-name {
          font-weight: 600;
          font-size: 0.95rem;
        }
        .status-pill {
          font-size: 0.75rem;
          padding: 2px 8px;
          border-radius: 10px;
          font-weight: 600;
          text-transform: uppercase;
        }
        .status-pill.online {
          background: rgba(16, 185, 129, 0.15);
          color: var(--accent-green);
        }
        .status-pill.offline {
          background: rgba(239, 68, 68, 0.15);
          color: var(--accent-red);
        }
        .device-row-stats {
          display: flex;
          gap: 15px;
          font-size: 0.8rem;
          color: var(--text-muted);
        }

        /* Main Workspace Section */
        .main-content-column {
          display: flex;
          flex-direction: column;
          gap: 25px;
        }
        .control-card {
          padding: 28px;
        }
        .card-header-actions {
          display: flex;
          justify-content: space-between;
          align-items: flex-start;
          border-bottom: 1px solid var(--border-color);
          padding-bottom: 18px;
          margin-bottom: 20px;
        }
        .device-id-label {
          font-size: 0.8rem;
          color: var(--text-muted);
          margin-top: 4px;
        }
        .unpair-link-btn {
          background: none;
          border: none;
          color: var(--accent-red);
          font-size: 0.85rem;
          font-weight: 600;
          cursor: pointer;
          transition: filter 0.2s;
        }
        .unpair-link-btn:hover {
          text-decoration: underline;
          filter: brightness(1.2);
        }

        .telemetry-widgets {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 20px;
        }
        .telemetry-box {
          display: flex;
          align-items: center;
          gap: 16px;
          padding: 16px;
          background: rgba(255, 255, 255, 0.02);
          border: 1px solid var(--border-color);
          border-radius: 12px;
        }
        .telemetry-icon {
          font-size: 1.8rem;
        }
        .telemetry-details {
          display: flex;
          flex-direction: column;
          gap: 4px;
          flex: 1;
        }
        .telemetry-title {
          font-size: 0.75rem;
          text-transform: uppercase;
          letter-spacing: 0.05em;
          color: var(--text-muted);
        }
        .battery-display {
          display: flex;
          align-items: center;
          gap: 10px;
        }
        .battery-bar-container {
          flex: 1;
          height: 16px;
          background: rgba(0, 0, 0, 0.3);
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 10px;
          position: relative;
          overflow: hidden;
        }
        .battery-fill {
          height: 100%;
          border-radius: 8px;
          transition: width 0.3s;
        }
        .battery-fill.good { background-color: var(--accent-green); }
        .battery-fill.mid { background-color: var(--accent-orange); }
        .battery-fill.low { background-color: var(--accent-red); }
        .battery-percent-label {
          position: absolute;
          width: 100%;
          text-align: center;
          font-size: 0.75rem;
          font-weight: 700;
          color: white;
          top: 50%;
          transform: translateY(-50%);
        }
        .charging-badge {
          font-size: 0.7rem;
          background: var(--accent-green);
          color: #000;
          font-weight: 700;
          padding: 1px 5px;
          border-radius: 4px;
        }
        .screen-badge {
          font-weight: 700;
          font-size: 0.9rem;
        }
        .screen-badge.locked { color: var(--accent-red); }
        .screen-badge.unlocked { color: var(--accent-green); }
        .screen-badge.unknown { color: var(--text-muted); }
        
        .network-badge {
          font-weight: 700;
          font-size: 0.9rem;
        }
        .network-badge.online { color: var(--accent-green); }
        .network-badge.offline { color: var(--accent-red); }

        /* Actions panel & logs grid */
        .actions-section {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 25px;
        }
        .actions-panel h3 {
          margin-bottom: 6px;
        }
        .section-desc {
          font-size: 0.85rem;
          color: var(--text-muted);
          margin-bottom: 20px;
        }
        .buttons-grid {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 15px;
          margin-bottom: 24px;
        }
        .action-trigger-btn {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: 10px;
          padding: 20px;
          background: rgba(255, 255, 255, 0.03);
          border: 1px solid var(--border-color);
          border-radius: 12px;
          color: var(--text-main);
          cursor: pointer;
          transition: all 0.2s;
        }
        .action-trigger-btn:hover:not(:disabled) {
          background: rgba(255, 255, 255, 0.06);
          border-color: var(--accent-blue);
          transform: translateY(-2px);
        }
        .action-trigger-btn:disabled {
          opacity: 0.4;
          cursor: not-allowed;
        }
        .action-trigger-btn .btn-icon {
          font-size: 1.8rem;
        }
        .action-trigger-btn .btn-text {
          font-size: 0.85rem;
          font-weight: 600;
        }

        .notify-input-panel h4 {
          font-size: 0.9rem;
          color: var(--text-main);
          margin-bottom: 10px;
        }
        .notify-field {
          display: flex;
          gap: 10px;
        }
        .notify-field input {
          flex: 1;
          padding: 10px 14px;
          background: rgba(0, 0, 0, 0.4);
          border: 1px solid var(--border-color);
          border-radius: 8px;
          color: var(--text-main);
          outline: none;
          font-family: inherit;
        }
        .notify-field input:focus {
          border-color: var(--accent-blue);
        }
        .notify-field button {
          padding: 10px 16px;
          background: var(--accent-blue);
          border: none;
          border-radius: 8px;
          color: white;
          font-weight: 600;
          cursor: pointer;
          transition: background 0.2s;
        }
        .notify-field button:hover:not(:disabled) {
          background: #2563eb;
        }
        .notify-field button:disabled {
          opacity: 0.5;
          cursor: not-allowed;
        }

        /* History panel ledger log list */
        .history-panel h3 {
          margin-bottom: 16px;
        }
        .logs-terminal {
          background: rgba(0, 0, 0, 0.5);
          border: 1px solid var(--border-color);
          border-radius: 12px;
          padding: 16px;
          height: 220px;
          overflow-y: auto;
          font-family: monospace;
          font-size: 0.8rem;
          display: flex;
          flex-direction: column;
          gap: 8px;
        }
        .terminal-empty {
          color: var(--text-muted);
          text-align: center;
          margin-top: 80px;
        }
        .terminal-row {
          display: flex;
          gap: 10px;
          line-height: 1.4;
          align-items: center;
        }
        .terminal-row .time {
          color: var(--text-muted);
        }
        .terminal-row .cmd {
          color: var(--accent-blue);
          font-weight: 700;
        }
        .terminal-row .payload {
          color: var(--text-main);
          flex: 1;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .terminal-row .status {
          font-weight: 700;
          padding: 1px 6px;
          border-radius: 4px;
          text-transform: uppercase;
          font-size: 0.7rem;
        }
        .terminal-row .status.pending {
          background: rgba(245, 158, 11, 0.15);
          color: var(--accent-orange);
        }
        .terminal-row .status.executed {
          background: rgba(16, 185, 129, 0.15);
          color: var(--accent-green);
        }
        .terminal-row .status.failed {
          background: rgba(239, 68, 68, 0.15);
          color: var(--accent-red);
        }

        .empty-active-view {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          text-align: center;
          padding: 100px 30px;
          min-height: 400px;
        }
        .empty-active-view .large-icon {
          font-size: 4rem;
          margin-bottom: 20px;
        }
        .empty-active-view h3 {
          font-size: 1.4rem;
          margin-bottom: 8px;
        }
        .empty-active-view p {
          color: var(--text-muted);
          font-size: 0.95rem;
          max-width: 350px;
        }
      `}</style>
    </div>
  );
}
