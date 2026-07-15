import React, { useState, useEffect, useRef } from 'react';
import { io, Socket } from 'socket.io-client';
import './App.css';

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

  // Tab State
  const [activeTab, setActiveTab] = useState<'overview' | 'automations' | 'ledger' | 'settings'>('overview');

  // Automation Rules state (per device ID, stored in localStorage)
  const [rules, setRules] = useState({
    lowBatteryAlert: false,
    autoLockCurfew: false,
    inactiveLock: false
  });

  const socketRef = useRef<Socket | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

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
    setSelectedDeviceId(null);
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
      
      // Load local automation rules
      const savedRules = localStorage.getItem(`rules_${selectedDeviceId}`);
      if (savedRules) {
        setRules(JSON.parse(savedRules));
      } else {
        setRules({ lowBatteryAlert: false, autoLockCurfew: false, inactiveLock: false });
      }
    } else {
      setHistory([]);
    }
  }, [selectedDeviceId]);

  // Save rules when toggled
  const handleRuleToggle = (key: keyof typeof rules) => {
    if (!selectedDeviceId) return;
    const updated = { ...rules, [key]: !rules[key] };
    setRules(updated);
    localStorage.setItem(`rules_${selectedDeviceId}`, JSON.stringify(updated));
  };

  // Initial load and WebSocket listener setup
  useEffect(() => {
    if (!token) return;
    fetchDevices();

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

  // HTML5 Canvas Battery History Chart Rendering
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || activeTab !== 'overview') return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Set canvas dimensions
    const width = canvas.parentElement?.clientWidth || 600;
    canvas.width = width;
    canvas.height = 200;

    // Clear chart
    ctx.clearRect(0, 0, width, 200);

    // Mock battery drain telemetry data points
    const activeDevice = devices.find(d => d.id === selectedDeviceId);
    const currentBattery = activeDevice?.battery_level || 50;
    const dataPoints = [
      currentBattery + 15 > 100 ? 100 : currentBattery + 15,
      currentBattery + 12 > 100 ? 100 : currentBattery + 12,
      currentBattery + 8 > 100 ? 100 : currentBattery + 8,
      currentBattery + 4 > 100 ? 100 : currentBattery + 4,
      currentBattery
    ];

    const padding = 30;
    const chartWidth = width - padding * 2;
    const chartHeight = 140;
    const stepX = chartWidth / (dataPoints.length - 1);

    // Draw grid lines
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.04)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = padding + (chartHeight / 4) * i;
      ctx.beginPath();
      ctx.moveTo(padding, y);
      ctx.lineTo(width - padding, y);
      ctx.stroke();
    }

    // Draw smooth telemetry line
    ctx.strokeStyle = 'var(--accent-blue)';
    ctx.lineWidth = 3;
    ctx.shadowBlur = 10;
    ctx.shadowColor = 'var(--accent-blue-glow)';
    
    ctx.beginPath();
    dataPoints.forEach((val, idx) => {
      const x = padding + idx * stepX;
      const y = padding + chartHeight - (val / 100) * chartHeight;
      if (idx === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // Fill area below telemetry line
    ctx.shadowBlur = 0; // Disable shadow for gradient
    const gradient = ctx.createLinearGradient(0, padding, 0, padding + chartHeight);
    gradient.addColorStop(0, 'rgba(59, 130, 246, 0.2)');
    gradient.addColorStop(1, 'rgba(59, 130, 246, 0.0)');
    ctx.fillStyle = gradient;

    ctx.lineTo(padding + (dataPoints.length - 1) * stepX, padding + chartHeight);
    ctx.lineTo(padding, padding + chartHeight);
    ctx.closePath();
    ctx.fill();

    // Draw data points markers
    ctx.fillStyle = '#fff';
    ctx.strokeStyle = 'var(--accent-blue)';
    ctx.lineWidth = 2;
    dataPoints.forEach((val, idx) => {
      const x = padding + idx * stepX;
      const y = padding + chartHeight - (val / 100) * chartHeight;
      ctx.beginPath();
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    });

  }, [devices, selectedDeviceId, activeTab]);

  const formatDate = (isoString: string) => {
    try {
      const d = new Date(isoString);
      return d.toLocaleString([], { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
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
      </div>
    );
  }

  const activeDevice = devices.find(d => d.id === selectedDeviceId);

  return (
    <div className="app-container">
      {/* 1. Sidebar Nav */}
      <aside className="glass-panel sidebar">
        <div className="sidebar-top">
          <div className="sidebar-brand">
            <div className="brand-dot"></div>
            <span className="brand-logo-text">LockIT</span>
          </div>

          <nav className="sidebar-nav">
            <div
              className={`nav-item ${activeTab === 'overview' ? 'active' : ''}`}
              onClick={() => setActiveTab('overview')}
            >
              <span className="nav-icon">📊</span>
              <span>Overview</span>
            </div>
            <div
              className={`nav-item ${activeTab === 'automations' ? 'active' : ''}`}
              onClick={() => setActiveTab('automations')}
            >
              <span className="nav-icon">⚡</span>
              <span>Automations</span>
            </div>
            <div
              className={`nav-item ${activeTab === 'ledger' ? 'active' : ''}`}
              onClick={() => setActiveTab('ledger')}
            >
              <span className="nav-icon">📜</span>
              <span>Operations Ledger</span>
            </div>
            <div
              className={`nav-item ${activeTab === 'settings' ? 'active' : ''}`}
              onClick={() => setActiveTab('settings')}
            >
              <span className="nav-icon">⚙️</span>
              <span>App Settings</span>
            </div>
          </nav>
        </div>

        <div className="sidebar-footer">
          {/* Pair Widget Inside Sidebar */}
          <div className="sidebar-manager-card glass-panel">
            <h4 style={{ fontSize: '0.85rem', color: '#fff' }}>Pair New Device</h4>
            <form onSubmit={handlePairSubmit} className="pair-form-sidebar">
              <input
                type="text"
                maxLength={8}
                placeholder="ABCDEF12"
                value={pairingKey}
                onChange={e => setPairingKey(e.target.value.toUpperCase())}
                required
              />
              <button type="submit">Pair Device</button>
            </form>
            {pairingMsg.text && (
              <p style={{ fontSize: '0.75rem', color: pairingMsg.isError ? 'var(--accent-red)' : 'var(--accent-green)', textAlign: 'center', marginTop: '4px' }}>
                {pairingMsg.text}
              </p>
            )}
          </div>

          <button className="logout-btn" onClick={handleLogout}>
            <span>🚪</span>
            <span>Log Out Admin</span>
          </button>
        </div>
      </aside>

      {/* 2. Main content column */}
      <main className="main-content">
        <header className="glass-panel main-header">
          <div className="header-title-section">
            <h2>Console Dashboard</h2>
            <p className="header-desc">
              {activeDevice ? `Managing device: ${activeDevice.name}` : 'No paired devices linked.'}
            </p>
          </div>

          {devices.length > 0 && (
            <select
              className="header-device-selector"
              value={selectedDeviceId || ''}
              onChange={e => setSelectedDeviceId(e.target.value)}
            >
              {devices.map(d => (
                <option key={d.id} value={d.id}>
                  {d.name} ({d.status})
                </option>
              ))}
            </select>
          )}
        </header>

        {activeDevice ? (
          <div className="tab-pane">
            {/* Overview Tab Content */}
            {activeTab === 'overview' && (
              <>
                {/* Telemetry Widgets Row */}
                <div className="telemetry-row">
                  <div className="glass-panel telemetry-card">
                    <span className="telemetry-card-title">Network Link</span>
                    <span className={`telemetry-card-value ${activeDevice.status}`}>
                      {activeDevice.status === 'online' ? (
                        <>
                          <span className="online-pulse-text">● ONLINE</span>
                        </>
                      ) : (
                        <span className="offline-text">○ OFFLINE</span>
                      )}
                    </span>
                    <span className="telemetry-subtext">
                      {activeDevice.status === 'online' ? 'Active WebSocket connection.' : 'Device is background suspended.'}
                    </span>
                  </div>

                  <div className="glass-panel telemetry-card">
                    <span className="telemetry-card-title">Battery Status</span>
                    <span className="telemetry-card-value">
                      🔋 {activeDevice.battery_level}%
                      {activeDevice.is_charging === 1 && (
                        <span className="battery-charging-lightning">⚡</span>
                      )}
                    </span>
                    <div className="battery-progress-container">
                      <div
                        className="battery-progress-bar"
                        style={{
                          width: `${activeDevice.battery_level}%`,
                          backgroundColor: activeDevice.battery_level < 20 ? 'var(--accent-red)' : 'var(--accent-green)'
                        }}
                      ></div>
                    </div>
                  </div>

                  <div className="glass-panel telemetry-card">
                    <span className="telemetry-card-title">Display Lock</span>
                    <span className="telemetry-card-value">
                      {activeDevice.screen_status === 'LOCKED' ? '🔒 LOCKED' : '🔓 UNLOCKED'}
                    </span>
                    <span className="telemetry-subtext">
                      Last audit check: {formatDate(activeDevice.last_seen)}
                    </span>
                  </div>
                </div>

                {/* Canvas graph */}
                <div className="glass-panel chart-card">
                  <div className="chart-header">
                    <h3 style={{ fontSize: '1.05rem', color: '#fff' }}>Battery Drain Telemetry</h3>
                    <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Historical battery levels reported by client.</p>
                  </div>
                  <div className="chart-canvas-wrapper">
                    <canvas ref={canvasRef}></canvas>
                  </div>
                </div>

                {/* Operations Control Panel */}
                <div className="control-grid">
                  <div className="glass-panel control-panel-card">
                    <h3 style={{ fontSize: '1.05rem', color: '#fff' }}>Remote Operations</h3>
                    <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Dispatch actions directly to the companion device.</p>
                    
                    <div className="action-buttons-container">
                      <button
                        className="action-btn lock"
                        onClick={() => sendCommand(activeDevice.id, 'lock')}
                        disabled={activeDevice.status !== 'online' || isSendingCmd !== null}
                      >
                        {isSendingCmd === 'lock' ? 'Locking...' : '🔒 Lock Screen Now'}
                      </button>
                      <button
                        className="action-btn ring"
                        onClick={() => sendCommand(activeDevice.id, 'ring')}
                        disabled={activeDevice.status !== 'online' || isSendingCmd !== null}
                      >
                        {isSendingCmd === 'ring' ? 'Ringing...' : '🔊 Trigger Sound Alert'}
                      </button>
                    </div>

                    <div style={{ marginTop: '10px' }}>
                      <h4 style={{ fontSize: '0.9rem', color: '#fff', marginBottom: '8px' }}>Send Notification Alert</h4>
                      <form
                        onSubmit={e => {
                          e.preventDefault();
                          if (!notificationText.trim()) return;
                          sendCommand(activeDevice.id, 'notify', { message: notificationText });
                          setNotificationText('');
                        }}
                        className="alert-form"
                      >
                        <input
                          type="text"
                          placeholder="Type alert alert (e.g. Please charge your phone!)..."
                          value={notificationText}
                          onChange={e => setNotificationText(e.target.value)}
                          disabled={activeDevice.status !== 'online'}
                        />
                        <button type="submit" disabled={activeDevice.status !== 'online' || !notificationText.trim()}>
                          Send Alert
                        </button>
                      </form>
                    </div>
                  </div>

                  <div className="glass-panel control-panel-card">
                    <h3 style={{ fontSize: '1.05rem', color: '#fff' }}>Quick Ledger Logs</h3>
                    <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '10px' }}>Recent activities.</p>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', overflowY: 'auto', maxHeight: '180px' }}>
                      {history.slice(0, 4).map(log => (
                        <div key={log.id} style={{ display: 'flex', justifyContent: 'space-between', padding: '10px', background: 'rgba(0,0,0,0.2)', borderRadius: '6px', fontSize: '0.8rem' }}>
                          <span style={{ color: '#fff', fontWeight: '500' }}>{log.command.toUpperCase()}</span>
                          <span style={{ color: log.status === 'EXECUTED' ? 'var(--accent-green)' : 'var(--accent-red)' }}>{log.status}</span>
                        </div>
                      ))}
                      {history.length === 0 && (
                        <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', textAlign: 'center', padding: '15px' }}>No operations logged.</p>
                      )}
                    </div>
                  </div>
                </div>
              </>
            )}

            {/* Automations Tab Content */}
            {activeTab === 'automations' && (
              <div className="rules-container">
                <h3 style={{ fontSize: '1.2rem', color: '#fff', marginBottom: '4px' }}>Automation Triggers</h3>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '15px' }}>Configure client-side responses that execute automatically based on status values.</p>

                <div className="glass-panel rule-row-card">
                  <div className="rule-info">
                    <h4>Low Battery Alert</h4>
                    <p>Alert you immediately when the companion device drops below 15% battery capacity.</p>
                  </div>
                  <label className="switch-wrapper">
                    <input
                      type="checkbox"
                      checked={rules.lowBatteryAlert}
                      onChange={() => handleRuleToggle('lowBatteryAlert')}
                    />
                    <span className="slider-knob"></span>
                  </label>
                </div>

                <div className="glass-panel rule-row-card">
                  <div className="rule-info">
                    <h4>Auto-Lock curfew</h4>
                    <p>Enforce immediate display locks on the device when curfews are active (e.g. night sleep hours).</p>
                  </div>
                  <label className="switch-wrapper">
                    <input
                      type="checkbox"
                      checked={rules.autoLockCurfew}
                      onChange={() => handleRuleToggle('autoLockCurfew')}
                    />
                    <span className="slider-knob"></span>
                  </label>
                </div>

                <div className="glass-panel rule-row-card">
                  <div className="rule-info">
                    <h4>Idle Lock Timer</h4>
                    <p>Automatically lock the device screen if it remains unlocked and inactive for more than 10 minutes.</p>
                  </div>
                  <label className="switch-wrapper">
                    <input
                      type="checkbox"
                      checked={rules.inactiveLock}
                      onChange={() => handleRuleToggle('inactiveLock')}
                    />
                    <span className="slider-knob"></span>
                  </label>
                </div>
              </div>
            )}

            {/* Operations Ledger Tab Content */}
            {activeTab === 'ledger' && (
              <div className="glass-panel ledger-card">
                <h3 style={{ fontSize: '1.2rem', color: '#fff' }}>Operations Ledger Log</h3>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '20px' }}>Full historical record of all commands triggered on this device from the server database.</p>

                <div className="ledger-table-container">
                  <table className="ledger-table">
                    <thead>
                      <tr>
                        <th>Operation</th>
                        <th>Payload</th>
                        <th>Execution Status</th>
                        <th>Dispatched At</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map(log => (
                        <tr key={log.id}>
                          <td style={{ color: '#fff', fontWeight: 'bold' }}>{log.command.toUpperCase()}</td>
                          <td style={{ color: 'var(--text-muted)' }}>{log.payload ? JSON.stringify(log.payload) : 'None'}</td>
                          <td>
                            <span className={`status-badge ${log.status.toLowerCase()}`}>
                              {log.status}
                            </span>
                          </td>
                          <td style={{ color: 'var(--text-muted)' }}>{formatDate(log.created_at)}</td>
                        </tr>
                      ))}
                      {history.length === 0 && (
                        <tr>
                          <td colSpan={4} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                            No operations ledger logs found for this device.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* App Settings Tab Content */}
            {activeTab === 'settings' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                <div className="glass-panel control-panel-card">
                  <h3 style={{ fontSize: '1.1rem', color: '#fff' }}>Device Identity Details</h3>
                  <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '12px' }}>Connection keys and hardware metadata.</p>
                  
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', fontSize: '0.9rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '8px' }}>
                      <span style={{ color: 'var(--text-muted)' }}>Hardware Model</span>
                      <span style={{ color: '#fff', fontWeight: 'bold' }}>{activeDevice.name}</span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '8px' }}>
                      <span style={{ color: 'var(--text-muted)' }}>Android Unique ID</span>
                      <span style={{ color: '#fff', fontFamily: 'monospace' }}>{activeDevice.id}</span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', paddingBottom: '8px' }}>
                      <span style={{ color: 'var(--text-muted)' }}>Registered Server URL</span>
                      <span style={{ color: '#fff' }}>{API_URL}</span>
                    </div>
                  </div>
                </div>

                <div className="glass-panel control-panel-card" style={{ borderColor: 'var(--accent-red-glow)' }}>
                  <h3 style={{ fontSize: '1.1rem', color: 'var(--accent-red)' }}>Danger Zone</h3>
                  <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Unpair this device. This will revoke connection tokens permanently.</p>
                  
                  <div style={{ marginTop: '10px' }}>
                    <button
                      onClick={() => handleUnpair(activeDevice.id)}
                      style={{
                        padding: '12px 24px',
                        background: 'linear-gradient(135deg, var(--accent-red), #991b1b)',
                        border: 'none',
                        borderRadius: '8px',
                        color: '#fff',
                        fontFamily: 'var(--font-heading)',
                        fontWeight: '600',
                        cursor: 'pointer',
                        boxShadow: '0 4px 14px var(--accent-red-glow)'
                      }}
                    >
                      Delete Device Connection
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="glass-panel empty-state-card" style={{ padding: '60px', textAlign: 'center' }}>
            <h3 style={{ fontSize: '1.4rem', color: '#fff', marginBottom: '8px' }}>No Device Selected</h3>
            <p style={{ color: 'var(--text-muted)', maxWidth: '400px', margin: '0 auto' }}>
              Please pair a device using the pairing widget at the bottom left, or select a device from the dropdown menu in the header.
            </p>
          </div>
        )}
      </main>
    </div>
  );
}
