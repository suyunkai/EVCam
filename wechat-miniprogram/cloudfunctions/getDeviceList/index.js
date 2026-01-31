// 云函数入口文件 - getDeviceList
// 获取用户绑定的设备列表（管理用）

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { page = 1, pageSize = 20 } = event;
  
  try {
    // 获取用户绑定的所有设备
    const skip = (page - 1) * pageSize;
    
    const devicesRes = await db.collection('devices')
      .where({
        boundUserId: openid
      })
      .orderBy('boundTime', 'desc')
      .skip(skip)
      .limit(pageSize)
      .get();
    
    const devices = devicesRes.data;
    
    // 处理设备数据，添加在线状态
    const HEARTBEAT_TIMEOUT = 60 * 1000;
    const now = Date.now();
    
    const processedDevices = devices.map(device => {
      const lastHeartbeat = device.lastHeartbeat ? new Date(device.lastHeartbeat).getTime() : 0;
      const isOnline = (now - lastHeartbeat) < HEARTBEAT_TIMEOUT;
      
      return {
        _id: device._id,
        deviceId: device.deviceId,
        deviceName: device.deviceName || 'EVCam 设备',
        deviceModel: device.deviceModel,
        online: isOnline,
        recording: device.recording || false,
        statusInfo: device.statusInfo || '',
        boundTime: device.boundTime,
        lastHeartbeat: device.lastHeartbeat
      };
    });
    
    // 获取总数
    const countRes = await db.collection('devices').where({
      boundUserId: openid
    }).count();
    
    return {
      success: true,
      devices: processedDevices,
      total: countRes.total,
      page: page,
      pageSize: pageSize
    };
    
  } catch (err) {
    console.error('获取设备列表失败:', err);
    return {
      success: false,
      message: '获取失败: ' + err.message
    };
  }
};
