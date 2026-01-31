// 云函数入口文件 - getDeviceStatus
// 获取设备状态

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { deviceId } = event;
  
  if (!deviceId) {
    return {
      success: false,
      message: '设备ID不能为空'
    };
  }
  
  try {
    // 获取设备信息
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在'
      };
    }
    
    const device = deviceRes.data[0];
    
    // 检查设备是否属于当前用户
    const isBound = device.boundUserId === openid;
    
    // 判断设备是否在线（心跳超时时间：45秒，给一些容错）
    const HEARTBEAT_TIMEOUT = 45 * 1000;
    
    // 处理 lastHeartbeat - 可能是数字时间戳或 Date 对象
    let lastHeartbeatTime = 0;
    if (device.lastHeartbeat) {
      if (typeof device.lastHeartbeat === 'number') {
        // 数字时间戳（毫秒）
        lastHeartbeatTime = device.lastHeartbeat;
      } else if (device.lastHeartbeat instanceof Date) {
        // Date 对象
        lastHeartbeatTime = device.lastHeartbeat.getTime();
      } else if (typeof device.lastHeartbeat === 'string') {
        // 字符串格式
        lastHeartbeatTime = new Date(device.lastHeartbeat).getTime();
      } else if (device.lastHeartbeat.$date) {
        // MongoDB 日期格式
        lastHeartbeatTime = new Date(device.lastHeartbeat.$date).getTime();
      }
    }
    
    const now = Date.now();
    const timeDiff = now - lastHeartbeatTime;
    const isOnline = lastHeartbeatTime > 0 && timeDiff < HEARTBEAT_TIMEOUT;
    
    console.log('设备状态检查:', {
      deviceId: deviceId,
      lastHeartbeat: device.lastHeartbeat,
      lastHeartbeatTime: lastHeartbeatTime,
      now: now,
      timeDiff: timeDiff,
      isOnline: isOnline
    });
    
    return {
      success: true,
      device: {
        deviceId: device.deviceId,
        deviceName: device.deviceName || 'EVCam 设备',
        online: isOnline,
        recording: device.recording || false,
        statusInfo: device.statusInfo || '',
        lastHeartbeat: device.lastHeartbeat,
        lastHeartbeatTime: lastHeartbeatTime,
        timeSinceHeartbeat: Math.floor(timeDiff / 1000),
        isBound: isBound,
        previewFileId: device.previewFileId || null,
        previewTime: device.previewTime || null
      }
    };
    
  } catch (err) {
    console.error('获取设备状态失败:', err);
    return {
      success: false,
      message: '获取状态失败: ' + err.message
    };
  }
};
