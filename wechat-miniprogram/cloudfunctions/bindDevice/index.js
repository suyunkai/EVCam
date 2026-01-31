// 云函数入口文件 - bindDevice
// 用于微信用户绑定设备（如果设备不存在则自动注册）

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
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { deviceId, deviceName } = event;
  
  if (!deviceId) {
    return {
      success: false,
      message: '设备ID不能为空'
    };
  }
  
  try {
    // 检查设备是否存在
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId
    }).get();
    
    let device;
    let isNewDevice = false;
    
    if (deviceRes.data.length === 0) {
      // 设备不存在，自动注册
      console.log('设备不存在，自动注册:', deviceId);
      
      const deviceSecret = generateSecret();
      const newDevice = {
        deviceId: deviceId,
        deviceName: deviceName || 'EVCam 设备',
        deviceModel: 'unknown',
        deviceSecret: deviceSecret,
        appVersion: 'unknown',
        boundUserId: openid,
        boundTime: db.serverDate(),
        lastHeartbeat: null,
        statusInfo: '',
        recording: false,
        registerTime: db.serverDate(),
        lastRegisterTime: db.serverDate(),
        createTime: db.serverDate(),
        updateTime: db.serverDate()
      };
      
      const addRes = await db.collection('devices').add({
        data: newDevice
      });
      
      device = { ...newDevice, _id: addRes._id };
      isNewDevice = true;
      
    } else {
      device = deviceRes.data[0];
      
      // 检查设备是否已被其他用户绑定
      if (device.boundUserId && device.boundUserId !== openid) {
        return {
          success: false,
          message: '设备已被其他用户绑定'
        };
      }
      
      // 更新设备绑定信息
      await db.collection('devices').doc(device._id).update({
        data: {
          boundUserId: openid,
          boundTime: db.serverDate(),
          deviceName: deviceName || device.deviceName || 'EVCam 设备',
          updateTime: db.serverDate()
        }
      });
    }
    
    // 记录绑定历史
    await db.collection('bind_history').add({
      data: {
        deviceId: deviceId,
        userId: openid,
        action: isNewDevice ? 'register_and_bind' : 'bind',
        createTime: db.serverDate()
      }
    });
    
    return {
      success: true,
      message: isNewDevice ? '设备注册并绑定成功' : '绑定成功',
      device: {
        deviceId: deviceId,
        deviceName: deviceName || device.deviceName || 'EVCam 设备'
      }
    };
    
  } catch (err) {
    console.error('绑定设备失败:', err);
    return {
      success: false,
      message: '绑定失败: ' + err.message
    };
  }
};
