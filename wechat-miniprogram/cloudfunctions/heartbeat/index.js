// 云函数入口文件 - heartbeat
// 车机端心跳上报

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const { deviceId, deviceSecret, statusInfo, recording } = event;
  
  if (!deviceId) {
    return {
      success: false,
      message: '设备ID不能为空'
    };
  }
  
  try {
    // 查找设备
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在，请先注册'
      };
    }
    
    const device = deviceRes.data[0];
    
    // 可选：验证设备密钥
    if (deviceSecret && device.deviceSecret && device.deviceSecret !== deviceSecret) {
      return {
        success: false,
        message: '设备认证失败'
      };
    }
    
    // 更新心跳时间和状态
    const updateData = {
      lastHeartbeat: db.serverDate(),
      updateTime: db.serverDate()
    };
    
    if (statusInfo !== undefined) {
      updateData.statusInfo = statusInfo;
    }
    
    if (recording !== undefined) {
      updateData.recording = recording;
    }
    
    await db.collection('devices').doc(device._id).update({
      data: updateData
    });
    
    // 检查是否有待执行的命令
    const pendingCommandsRes = await db.collection('commands').where({
      deviceId: deviceId,
      status: 'pending'
    }).count();
    
    return {
      success: true,
      message: '心跳已更新',
      hasPendingCommands: pendingCommandsRes.total > 0
    };
    
  } catch (err) {
    console.error('心跳上报失败:', err);
    return {
      success: false,
      message: '心跳失败: ' + err.message
    };
  }
};
