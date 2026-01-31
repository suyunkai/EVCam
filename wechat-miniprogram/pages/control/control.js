// pages/control/control.js
const app = getApp();

Page({
  data: {
    device: null,
    deviceOnline: false,
    recording: false,
    recordDuration: 60,  // 默认录制时长（秒）
    durationOptions: [30, 60, 120, 180, 300],  // 可选时长
    statusInfo: '',
    lastCommand: null,
    commandStatus: '',  // pending, success, failed
    commandMessage: '',  // 命令执行提示信息
    showCommandTip: false,  // 是否显示命令提示
    commandCountdown: 0  // 倒计时秒数
  },

  countdownTimer: null,
  autoCompleteTimer: null,

  onLoad: function() {
    const device = app.globalData.boundDevice;
    if (!device) {
      wx.showToast({
        title: '未绑定设备',
        icon: 'none'
      });
      setTimeout(() => wx.navigateBack(), 1500);
      return;
    }

    this.setData({ device: device });
    this.refreshStatus();
  },

  onShow: function() {
    // 每次显示刷新状态
    if (this.data.device) {
      this.refreshStatus();
    }
  },

  onHide: function() {
    this.clearTimers();
  },

  onUnload: function() {
    this.clearTimers();
  },

  clearTimers: function() {
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
    if (this.autoCompleteTimer) {
      clearTimeout(this.autoCompleteTimer);
      this.autoCompleteTimer = null;
    }
  },

  // 刷新设备状态
  refreshStatus: function() {
    wx.cloud.callFunction({
      name: 'getDeviceStatus',
      data: {
        deviceId: this.data.device.deviceId
      }
    }).then(res => {
      if (res.result && res.result.success) {
        this.setData({
          deviceOnline: res.result.device.online || false,
          statusInfo: res.result.device.statusInfo || '',
          recording: res.result.device.recording || false
        });
      } else {
        this.setData({ deviceOnline: false });
      }
    }).catch(err => {
      console.error('获取状态失败', err);
      this.setData({ deviceOnline: false });
    });
  },

  // 选择录制时长
  onDurationChange: function(e) {
    const index = e.detail.value;
    this.setData({
      recordDuration: this.data.durationOptions[index]
    });
  },

  // 一键录像
  startRecord: function() {
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }

    const duration = this.data.recordDuration;
    wx.showModal({
      title: '开始录像',
      content: `将录制 ${duration} 秒视频，录制完成需要约 ${duration + 30} 秒，请稍后在"文件"中查看。确认开始？`,
      success: (res) => {
        if (res.confirm) {
          this.sendRecordCommand(duration);
        }
      }
    });
  },

  // 发送录像命令（带特殊提示）
  sendRecordCommand: function(duration) {
    wx.showLoading({ title: '发送中...' });

    this.setData({
      lastCommand: 'record',
      commandStatus: 'pending'
    });

    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.device.deviceId,
        command: 'record',
        params: { duration: duration }
      }
    }).then(res => {
      wx.hideLoading();

      if (res.result && res.result.success) {
        // 设置倒计时和提示
        const waitTime = duration + 30;  // 录制时间 + 30秒处理时间
        this.setData({ 
          commandStatus: 'pending',
          showCommandTip: true,
          commandCountdown: waitTime,
          commandMessage: `录像中，预计 ${waitTime} 秒后完成，请稍后在"文件"中查看录像`
        });

        // 开始倒计时
        this.startCountdown(waitTime, 'record');

      } else {
        this.setData({ commandStatus: 'failed' });
        wx.showToast({
          title: res.result?.message || '发送失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      this.setData({ commandStatus: 'failed' });
      console.error('发送命令失败', err);
      wx.showToast({
        title: '发送失败，请重试',
        icon: 'none'
      });
    });
  },

  // 一键拍照
  takePhoto: function() {
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }

    wx.showModal({
      title: '拍照',
      content: '拍照最长需要30秒完成，完成后请在"文件"中查看照片。确认拍照？',
      success: (res) => {
        if (res.confirm) {
          this.sendPhotoCommand();
        }
      }
    });
  },

  // 发送拍照命令（带特殊提示）
  sendPhotoCommand: function() {
    wx.showLoading({ title: '发送中...' });

    this.setData({
      lastCommand: 'photo',
      commandStatus: 'pending'
    });

    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.device.deviceId,
        command: 'photo',
        params: {}
      }
    }).then(res => {
      wx.hideLoading();

      if (res.result && res.result.success) {
        // 设置倒计时和提示（拍照最长30秒）
        const waitTime = 30;
        this.setData({ 
          commandStatus: 'pending',
          showCommandTip: true,
          commandCountdown: waitTime,
          commandMessage: '拍照中，最长需要30秒，请稍后在"文件"中查看照片'
        });

        // 开始倒计时
        this.startCountdown(waitTime, 'photo');

      } else {
        this.setData({ commandStatus: 'failed' });
        wx.showToast({
          title: res.result?.message || '发送失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      this.setData({ commandStatus: 'failed' });
      console.error('发送命令失败', err);
      wx.showToast({
        title: '发送失败，请重试',
        icon: 'none'
      });
    });
  },

  // 开始倒计时
  startCountdown: function(seconds, commandType) {
    this.clearTimers();

    this.countdownTimer = setInterval(() => {
      let countdown = this.data.commandCountdown - 1;
      if (countdown <= 0) {
        this.clearTimers();
        // 自动判断完成
        this.onCommandAutoComplete(commandType);
      } else {
        this.setData({ commandCountdown: countdown });
      }
    }, 1000);
  },

  // 命令自动完成（倒计时结束）
  onCommandAutoComplete: function(commandType) {
    let message = '';
    if (commandType === 'photo') {
      message = '拍照已完成，请在"文件"中查看照片';
    } else if (commandType === 'record') {
      message = '录像已完成，请在"文件"中查看录像';
    }

    this.setData({
      commandStatus: 'success',
      showCommandTip: true,
      commandCountdown: 0,
      commandMessage: message
    });

    wx.showToast({
      title: message,
      icon: 'success',
      duration: 3000
    });

    // 5秒后隐藏提示
    setTimeout(() => {
      this.setData({ showCommandTip: false });
    }, 5000);
  },

  // 隐藏命令提示
  hideCommandTip: function() {
    this.clearTimers();
    this.setData({ 
      showCommandTip: false,
      commandCountdown: 0
    });
  },

  // 跳转到文件页面
  goToFiles: function() {
    wx.navigateTo({
      url: '/pages/files/files'
    });
  },

  // 跳转到预览页面
  goToPreview: function() {
    wx.navigateTo({
      url: '/pages/preview/preview'
    });
  },

  // 开始持续录制
  startContinuousRecord: function() {
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }

    this.sendCommand('start_recording', {});
  },

  // 停止录制
  stopRecord: function() {
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }

    this.sendCommand('stop_recording', {});
  },

  // 查询状态
  queryStatus: function() {
    if (!this.data.deviceOnline) {
      wx.showToast({
        title: '设备离线',
        icon: 'none'
      });
      return;
    }

    this.sendCommand('status', {});
  },

  // 发送命令（通用）
  sendCommand: function(command, params) {
    wx.showLoading({ title: '发送中...' });

    this.setData({
      lastCommand: command,
      commandStatus: 'pending'
    });

    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.device.deviceId,
        command: command,
        params: params
      }
    }).then(res => {
      wx.hideLoading();

      if (res.result && res.result.success) {
        this.setData({ commandStatus: 'success' });

        let tip = '';
        switch (command) {
          case 'start_recording':
            tip = '开始录制指令已发送';
            break;
          case 'stop_recording':
            tip = '停止录制指令已发送';
            break;
          case 'status':
            tip = '状态查询已发送';
            break;
          default:
            tip = '指令已发送';
        }

        wx.showToast({
          title: tip,
          icon: 'success',
          duration: 2000
        });

        // 延迟刷新状态
        setTimeout(() => this.refreshStatus(), 3000);

      } else {
        this.setData({ commandStatus: 'failed' });
        wx.showToast({
          title: res.result?.message || '发送失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      this.setData({ commandStatus: 'failed' });
      console.error('发送命令失败', err);
      wx.showToast({
        title: '发送失败，请重试',
        icon: 'none'
      });
    });
  },

  // 下拉刷新
  onPullDownRefresh: function() {
    this.refreshStatus();
    wx.stopPullDownRefresh();
  }
});
