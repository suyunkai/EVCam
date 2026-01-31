// 云函数入口文件 - unbindDevice
// 用于微信用户解绑设备

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
    // 检查设备是否存在且属于当前用户
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId,
      boundUserId: openid
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在或不属于您'
      };
    }
    
    const device = deviceRes.data[0];
    
    // 清除设备绑定信息
    await db.collection('devices').doc(device._id).update({
      data: {
        boundUserId: '',
        boundTime: null
      }
    });
    
    // 记录解绑历史
    await db.collection('bind_history').add({
      data: {
        deviceId: deviceId,
        userId: openid,
        action: 'unbind',
        createTime: db.serverDate()
      }
    });
    
    return {
      success: true,
      message: '解绑成功'
    };
    
  } catch (err) {
    console.error('解绑设备失败:', err);
    return {
      success: false,
      message: '解绑失败: ' + err.message
    };
  }
};
