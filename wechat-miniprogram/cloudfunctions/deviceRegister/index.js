// 云函数入口文件 - deviceRegister
// 车机端设备注册

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 生成随机密钥
function generateSecret(length = 32) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

// 云函数入口函数
exports.main = async (event, context) => {
  const { deviceId, deviceName, deviceModel, appVersion } = event;
  
  if (!deviceId) {
    return {
      success: false,
      message: '设备ID不能为空'
    };
  }
  
  try {
    // 检查设备是否已存在
    const existingRes = await db.collection('devices').where({
      deviceId: deviceId
    }).get();
    
    if (existingRes.data.length > 0) {
      // 设备已存在，更新信息
      const device = existingRes.data[0];
      
      await db.collection('devices').doc(device._id).update({
        data: {
          deviceName: deviceName || device.deviceName,
          deviceModel: deviceModel || device.deviceModel,
          appVersion: appVersion || device.appVersion,
          lastRegisterTime: db.serverDate(),
          updateTime: db.serverDate()
        }
      });
      
      return {
        success: true,
        message: '设备信息已更新',
        isNew: false,
        deviceSecret: device.deviceSecret
      };
    }
    
    // 新设备注册
    const deviceSecret = generateSecret();
    
    const newDevice = {
      deviceId: deviceId,
      deviceName: deviceName || 'EVCam 设备',
      deviceModel: deviceModel || 'unknown',
      deviceSecret: deviceSecret,
      appVersion: appVersion || 'unknown',
      boundUserId: '',
      boundTime: null,
      lastHeartbeat: null,
      statusInfo: '',
      recording: false,
      registerTime: db.serverDate(),
      lastRegisterTime: db.serverDate(),
      createTime: db.serverDate(),
      updateTime: db.serverDate()
    };
    
    await db.collection('devices').add({
      data: newDevice
    });
    
    return {
      success: true,
      message: '设备注册成功',
      isNew: true,
      deviceSecret: deviceSecret
    };
    
  } catch (err) {
    console.error('设备注册失败:', err);
    return {
      success: false,
      message: '注册失败: ' + err.message
    };
  }
};
