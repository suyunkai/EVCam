// pages/index/index.js
const app = getApp();

Page({
  data: {
    boundDevice: null,
    deviceOnline: false,
    loading: true,
    commandPending: false,
    commandStatus: '',  // pending, completed, failed
    commandMessage: '',
    lastHeartbeatInfo: ''  // 心跳调试信息
  },

  refreshTimer: null,
  pollTimer: null,

  onLoad: function() {
    this.checkDevice();
  },

  onShow: function() {
    // 每次显示页面时刷新设备状态
    this.checkDevice();
    // 启动定时刷新
    this.startAutoRefresh();
  },

  onHide: function() {
    this.stopAutoRefresh();
    this.stopCommandPoll();
  },

  onUnload: function() {
    this.stopAutoRefresh();
    this.stopCommandPoll();
  },

  // 启动自动刷新（每10秒）
  startAutoRefresh: function() {
    this.stopAutoRefresh();
    this.refreshTimer = setInterval(() => {
      if (this.data.boundDevice && !this.data.loading) {
        this.refreshDeviceStatus(true);  // 静默刷新
      }
    }, 10000);
  },

  stopAutoRefresh: function() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  },

  // 检查设备绑定状态
  checkDevice: function() {
    const device = app.globalData.boundDevice;
    
    if (device) {
      this.setData({ 
        boundDevice: device,
        loading: true
      });
      this.refreshDeviceStatus();
    } else {
      this.setData({ 
        boundDevice: null,
        deviceOnline: false,
        loading: false
      });
    }
  },

  // 刷新设备状态
  refreshDeviceStatus: function(silent = false) {
    if (!this.data.boundDevice) return;

    if (!silent) {
      this.setData({ loading: true });
    }

    wx.cloud.callFunction({
      name: 'getDeviceStatus',
      data: {
        deviceId: this.data.boundDevice.deviceId
      }
    }).then(res => {
      console.log('设备状态返回:', res.result);
      if (res.result && res.result.success) {
        const device = res.result.device;
        // 生成调试信息
        let lastHeartbeatInfo = '';
        if (device.timeSinceHeartbeat !== undefined) {
          if (device.timeSinceHeartbeat > 60) {
            lastHeartbeatInfo = `上次心跳: ${Math.floor(device.timeSinceHeartbeat / 60)}分钟前`;
          } else {
            lastHeartbeatInfo = `上次心跳: ${device.timeSinceHeartbeat}秒前`;
          }
        } else if (!device.lastHeartbeat) {
          lastHeartbeatInfo = '从未收到心跳';
        }
        
        this.setData({
          deviceOnline: device.online || false,
          boundDevice: {
            ...this.data.boundDevice,
            ...device
          },
          lastHeartbeatInfo: lastHeartbeatInfo,
          loading: false
        });
      } else {
        this.setData({ 
          deviceOnline: false,
          lastHeartbeatInfo: res.result?.message || '状态获取失败',
          loading: false
        });
      }
    }).catch(err => {
      console.error('获取设备状态失败', err);
      this.setData({ 
        deviceOnline: false,
        lastHeartbeatInfo: '网络错误',
        loading: false
      });
    });
  },

  // 下拉刷新
  onPullDownRefresh: function() {
    this.refreshDeviceStatus();
    wx.stopPullDownRefresh();
  },

  // 快捷录像
  quickRecord: function() {
    if (!this.data.deviceOnline || this.data.commandPending) return;
    
    this.sendQuickCommand('record', { duration: 60 }, '录像指令已发送，正在执行...');
  },

  // 快捷拍照
  quickPhoto: function() {
    if (!this.data.deviceOnline || this.data.commandPending) return;
    
    this.sendQuickCommand('photo', {}, '拍照指令已发送，正在执行...');
  },

  // 发送快捷命令
  sendQuickCommand: function(command, params, message) {
    this.setData({
      commandPending: true,
      commandStatus: 'pending',
      commandMessage: message
    });

    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.boundDevice.deviceId,
        command: command,
        params: params
      }
    }).then(res => {
      if (res.result && res.result.success) {
        const commandId = res.result.commandId;
        // 开始轮询命令状态
        this.startCommandPoll(commandId, command);
      } else {
        this.setData({
          commandPending: false,
          commandStatus: 'failed',
          commandMessage: res.result?.message || '发送失败'
        });
        this.clearCommandStatus();
      }
    }).catch(err => {
      console.error('发送命令失败', err);
      this.setData({
        commandPending: false,
        commandStatus: 'failed',
        commandMessage: '发送失败，请重试'
      });
      this.clearCommandStatus();
    });
  },

  // 开始轮询命令状态
  startCommandPoll: function(commandId, command) {
    this.stopCommandPoll();
    let pollCount = 0;
    
    const poll = () => {
      pollCount++;
      
      if (pollCount > 60) {  // 最多60秒
        this.setData({
          commandPending: false,
          commandStatus: 'failed',
          commandMessage: '等待超时，请在文件列表中查看结果'
        });
        this.clearCommandStatus();
        return;
      }

      wx.cloud.callFunction({
        name: 'commandPoll',
        data: { commandId: commandId }
      }).then(res => {
        if (res.result && res.result.success && res.result.command) {
          const cmd = res.result.command;
          
          if (cmd.status === 'completed') {
            this.stopCommandPoll();
            this.setData({
              commandPending: false,
              commandStatus: 'completed',
              commandMessage: command === 'photo' ? '拍照完成！' : '录像完成！'
            });
            this.clearCommandStatus();
            
            wx.showToast({
              title: command === 'photo' ? '拍照成功' : '录像成功',
              icon: 'success'
            });
            
          } else if (cmd.status === 'failed') {
            this.stopCommandPoll();
            this.setData({
              commandPending: false,
              commandStatus: 'failed',
              commandMessage: cmd.result || '执行失败'
            });
            this.clearCommandStatus();
            
          } else if (cmd.status === 'executing') {
            this.setData({
              commandMessage: command === 'photo' ? '正在拍照...' : '正在录像...'
            });
          }
        }
      }).catch(err => {
        console.error('轮询失败', err);
      });
    };

    poll();
    this.pollTimer = setInterval(poll, 1000);
  },

  stopCommandPoll: function() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  },

  // 3秒后清除命令状态
  clearCommandStatus: function() {
    setTimeout(() => {
      if (!this.data.commandPending) {
        this.setData({
          commandStatus: '',
          commandMessage: ''
        });
      }
    }, 3000);
  },

  // 跳转到扫码绑定
  goToScan: function() {
    wx.navigateTo({
      url: '/pages/scan/scan'
    });
  },

  // 跳转到设备控制
  goToControl: function() {
    if (!this.data.boundDevice) {
      wx.showToast({
        title: '请先绑定设备',
        icon: 'none'
      });
      return;
    }
    wx.navigateTo({
      url: '/pages/control/control'
    });
  },

  // 跳转到实时预览
  goToPreview: function() {
    if (!this.data.boundDevice) {
      wx.showToast({
        title: '请先绑定设备',
        icon: 'none'
      });
      return;
    }
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }
    wx.navigateTo({
      url: '/pages/preview/preview'
    });
  },

  // 跳转到文件列表
  goToFiles: function() {
    if (!this.data.boundDevice) {
      wx.showToast({
        title: '请先绑定设备',
        icon: 'none'
      });
      return;
    }
    wx.navigateTo({
      url: '/pages/files/files'
    });
  },

  // 解绑设备
  unbindDevice: function() {
    wx.showModal({
      title: '解绑设备',
      content: '确定要解绑当前设备吗？解绑后需要重新扫码绑定。',
      success: (res) => {
        if (res.confirm) {
          this.doUnbind();
        }
      }
    });
  },

  doUnbind: function() {
    wx.showLoading({ title: '解绑中...' });

    wx.cloud.callFunction({
      name: 'unbindDevice',
      data: {
        deviceId: this.data.boundDevice.deviceId
      }
    }).then(res => {
      wx.hideLoading();
      if (res.result && res.result.success) {
        app.clearBoundDevice();
        this.setData({
          boundDevice: null,
          deviceOnline: false
        });
        wx.showToast({
          title: '已解绑',
          icon: 'success'
        });
      } else {
        wx.showToast({
          title: res.result?.message || '解绑失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      console.error('解绑失败', err);
      wx.showToast({
        title: '解绑失败',
        icon: 'none'
      });
    });
  }
});
