// app.js
App({
  onLaunch: function () {
    if (!wx.cloud) {
      console.error('请使用 2.2.3 或以上的基础库以使用云能力');
      return;
    }
    
    // 初始化云开发环境
    wx.cloud.init({
      env: 'cloudbase-0gt2twhpdc512c30',
      traceUser: true,
    });

    this.globalData = {
      // 已绑定的设备信息
      boundDevice: null,
      // 用户信息
      userInfo: null
    };

    // 加载已绑定的设备
    this.loadBoundDevice();
  },

  // 加载已绑定的设备
  loadBoundDevice: function() {
    const device = wx.getStorageSync('boundDevice');
    if (device) {
      this.globalData.boundDevice = device;
      // 验证绑定是否仍然有效
      this.verifyBinding(device.deviceId);
    }
  },

  // 验证绑定
  verifyBinding: function(deviceId) {
    wx.cloud.callFunction({
      name: 'getDeviceStatus',
      data: { deviceId: deviceId }
    }).then(res => {
      if (res.result && res.result.success) {
        // 绑定有效，更新设备状态
        this.globalData.boundDevice = {
          ...this.globalData.boundDevice,
          ...res.result.device
        };
      } else {
        // 绑定已失效
        console.log('设备绑定已失效');
        // 不立即清除，保留本地记录
      }
    }).catch(err => {
      console.error('验证绑定失败', err);
    });
  },

  // 保存绑定的设备
  saveBoundDevice: function(device) {
    this.globalData.boundDevice = device;
    wx.setStorageSync('boundDevice', device);
  },

  // 清除绑定
  clearBoundDevice: function() {
    this.globalData.boundDevice = null;
    wx.removeStorageSync('boundDevice');
  },

  globalData: {
    boundDevice: null,
    userInfo: null
  }
});
