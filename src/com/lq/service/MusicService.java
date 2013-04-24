/*   
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lq.service;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.provider.MediaStore.Audio.Media;
import android.util.Log;
import android.widget.Toast;

import com.lq.activity.MusicPlayerActivity;
import com.lq.activity.R;
import com.lq.entity.MusicItem;
import com.lq.fragment.LocalMusicFragment;

/**
 * 这是处理音乐回放的服务，在应用中对媒体的所有处理都交给这个服务。
 */
public class MusicService extends Service implements OnCompletionListener,
		OnPreparedListener, OnErrorListener, MusicFocusable {

	/**
	 * 自定义的一个服务连接监听器。 <br>
	 * 在显示组件与服务组件连接时，用以将业务数据通知并传递给显示层进行初始化；<br>
	 * 在显示组件与服务组件断开连接时，通知显示层。
	 */
	public interface OnServiceConnectionListener {
		/**
		 * 在调用MusicService里的
		 * {@linkplain com.lq.service.MusicService#registerOnServiceConnectionListener
		 * registerOnServiceConnectionListener()} 方法后调用此方法
		 * 
		 * @param currentPlayerState
		 *            当前音乐播放状态
		 * @param playingSong
		 *            当前播放的歌曲
		 * @param currenPlayPosition
		 *            当前已经播放到的位置
		 * @param playMode
		 *            当前播放模式（顺序播放、列表循环、单曲循环、随机播放）
		 * */
		public abstract void onServiceConnected(State currentPlayerState,
				MusicItem playingSong, int currenPlayPosition, int playMode);

		/**
		 * 在调用MusicService里的
		 * {@linkplain com.lq.service.MusicService#unregisterOnServiceConnectionListener
		 * unregisterOnServiceConnectionListener()} 方法后调用此方法
		 */
		public abstract void onServiceDisconnected();
	}

	/** 定义音乐回放时一系列状态变化时的回调接口 */
	public interface OnPlaybackStateChangeListener {
		/** 音乐开始播放时调用此方法 */
		public abstract void onMusicPlayed();

		/** 音乐播放停止时调用此方法 */
		public abstract void onMusicPaused();

		/** 音乐播放暂停时调用此方法 */
		public abstract void onMusicStopped();

		/**
		 * 播放新的歌曲时调用此方法
		 * 
		 * @param playingSong
		 *            新的歌曲信息
		 * */
		public abstract void onPlayNewSong(MusicItem playingSong);

		/**
		 * 播放模式改变时调用此方法
		 * 
		 * @param playMode
		 *            播放模式
		 * */
		public abstract void onPlayModeChanged(int playMode);

		/**
		 * 播放的音乐进度改变时调用此方法
		 * 
		 * @param currentMillis
		 *            当前播放进度 （已播放的毫秒数）
		 * */
		public abstract void onPlayProgressUpdate(int currentMillis);
	}

	public class MusicPlaybackLocalBinder extends Binder {
		/** 返回本service的实例到客户端，于是客户端可以调用本service的公开方法 */
		public MusicService getService() {
			return MusicService.this;
		}
	}

	// 打印调试信息用的标记
	final static String TAG = MusicService.class.getSimpleName();

	// 这里定义的是我们准备处理的Intent的各种动作标记，在这里定义仅仅是为了方便引用，
	// 真正要让本service处理这些动作，要在AndroidManifest.xml中<service>里的<action>标签内声明它们
	public static final String ACTION_PLAY = "com.lq.musicplayer.action.PLAY";
	public static final String ACTION_PAUSE = "com.lq.musicplayer.action.PAUSE";
	public static final String ACTION_STOP = "com.lq.musicplayer.action.STOP";
	public static final String ACTION_PREVIOUS = "com.lq.musicplayer.action.PREVIOUS";
	public static final String ACTION_NEXT = "com.lq.musicplayer.action.NEXT";

	// 消息类型
	public static final int MESSAGE_UPDATE_PLAYING_SONG_PROGRESS = 1;

	public static final int PLAYMODE_REPEAT_SINGLE = 0;
	public static final int PLAYMODE_REPEAT = 1;
	public static final int PLAYMODE_SEQUENTIAL = 2;
	public static final int PLAYMODE_SHUFFLE = 3;

	/** 0代表单曲循环，1代表列表循环，2代表顺序播放，3代表随机播放 */
	int mPlayMode = 1;

	/** 服务连接观察者集合 */
	ArrayList<OnServiceConnectionListener> mOnServiceConnectionListeners = new ArrayList<MusicService.OnServiceConnectionListener>();

	/** 回放状态变化的观察者集合 */
	ArrayList<OnPlaybackStateChangeListener> mOnPlaybackStateChangeListeners = new ArrayList<MusicService.OnPlaybackStateChangeListener>();

	MusicPlaybackLocalBinder mBinder = new MusicPlaybackLocalBinder();

	// 当前播放列表
	List<MusicItem> mPlayList = new ArrayList<MusicItem>();

	// 丢失音频焦点，我们为媒体播放设置一个低音量(1.0f为最大)，而不是停止播放
	public static final float DUCK_VOLUME = 0.1f;

	// 我们的媒体播放控制器
	MediaPlayer mPlayer = null;

	// 音频焦点的辅助类（API LEVEL > 8 时才能使用）
	AudioFocusHelper mAudioFocusHelper = null;

	/**
	 * 指示本服务的当前状态(Preparing,Playing,Paused,Stopped)
	 * */
	public enum State {
		// Retrieving, // 正在检索媒体文件
		Stopped, // MediaPlayer已经停止工作，不再准备播放
		Preparing, // MediaPlayer正在准备中
		Playing, // 正在播放（MediaPlayer已经准备好了）
					// （但是当丢失音频焦点时，MediaPlayer在此状态下实际上也许已经暂停了，
					// 但是我们仍然保持这个状态，以表明我们必须在一获得音频焦点时就返回播放状态）
		Paused // 播放暂停 (MediaPlayer处于准备好了的状态)
	};

	private State mState = State.Stopped;

	// 定义音频焦点的相关状态
	enum AudioFocus {
		NoFocusNoDuck, // 没有音频焦点，也不能低声播放
		NoFocusCanDuck, // 没有音频焦点，可以低声播放
		Focused // 有音频焦点，尽情大声播放吧
	}

	// 当前音频焦点状态
	AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

	/** 当前播放列表的播放队列，记录当前播放列表歌曲播放顺序 */
	LinkedList<Integer> mPlayQueue = new LinkedList<Integer>();

	boolean mHasPlayList = false;

	int mPlayingSongPos = 0;
	int mToBePlayedSongPos = 0;

	// 我们要播放的音乐是否是来自网络的媒体流
	boolean mIsStreaming = false;

	// 当从网络获取媒体流时保持一个Wifi锁，防止设备突然与Wifi连接的音频断开
	WifiLock mWifiLock;

	// 提示播放的通知的ID
	final int NOTIFICATION_ID = 1;

	AudioManager mAudioManager;
	NotificationManager mNotificationManager;

	Notification mNotification = null;

	Random mRandom = new Random();

	/** Service的Handler,可以延迟指定时间发送消息，而messenger不可以延时发送消息 */
	ServiceIncomingHandler mServiceHandler = new ServiceIncomingHandler(
			MusicService.this);

	/** 处理来自客户端的消息 */
	private static class ServiceIncomingHandler extends Handler {
		// 使用弱引用，避免Handler造成的内存泄露(Message持有Handler的引用，内部定义的Handler类持有外部类的引用)
		WeakReference<MusicService> mServiceWeakReference = null;
		MusicService mService = null;

		public ServiceIncomingHandler(MusicService service) {
			mServiceWeakReference = new WeakReference<MusicService>(service);
			mService = mServiceWeakReference.get();
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_UPDATE_PLAYING_SONG_PROGRESS:
				if (mService.mState == State.Playing) {
					for (int i = 0; i < mService.mOnPlaybackStateChangeListeners
							.size(); i++) {
						mService.mOnPlaybackStateChangeListeners.get(i)
								.onPlayProgressUpdate(
										mService.mPlayer.getCurrentPosition());
					}
					mService.mServiceHandler.sendEmptyMessageDelayed(
							MESSAGE_UPDATE_PLAYING_SONG_PROGRESS, 1000);
				}
				break;
			default:
				super.handleMessage(msg);
			}
		}
	}

	public void registerOnServiceConnectionListener(
			OnServiceConnectionListener listener) {
		mOnServiceConnectionListeners.add(listener);

		// 传递当前播放歌曲信息
		MusicItem item = null;
		int currentPlayPos = 0;
		if (mState == State.Playing || mState == State.Paused) {
			item = mPlayList.get(mPlayingSongPos);
			currentPlayPos = mPlayer.getCurrentPosition();
		}

		// 通知观察者服务服务已经连接上，并传递初始化数据
		listener.onServiceConnected(mState, item, currentPlayPos, mPlayMode);
	}

	public void unregisterOnServiceConnectionListener(
			OnServiceConnectionListener listener) {
		listener.onServiceDisconnected();
		mOnServiceConnectionListeners.remove(listener);
	}

	public void registerOnPlaybackStateChangeListener(
			OnPlaybackStateChangeListener listener) {
		mOnPlaybackStateChangeListeners.add(listener);
	}

	public void unregisterOnPlaybackStateChangeListener(
			OnPlaybackStateChangeListener listener) {
		mOnPlaybackStateChangeListeners.remove(listener);
	}

	@Override
	public void onCreate() {
		Log.i(TAG, "debug: Creating MusicService");

		// 创建Wifi锁（并没有获得该锁，仅仅是创建出来）
		mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
				.createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

		mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

		// 创建音频焦点辅助类，API LEVEL > 8 时SDK才支持音频焦点这个特性
		if (android.os.Build.VERSION.SDK_INT >= 8)
			mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(),
					this);
		else
			mAudioFocus = AudioFocus.Focused; // no focus feature, so we always
												// "have" audio focus
	}

	/**
	 * 通过startService()启动本服务会调用此方法，在此接受并处理发送方传递的Intent。
	 * 根据传递过来的Intent的action来作出其要求的处理。
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent.getAction();

		if (action.equals(ACTION_PLAY)) {
			if (mHasPlayList) {
				int flag = intent.getIntExtra(
						LocalMusicFragment.FLAG_PLAY_ITEM, 0);
				if (flag == 1) {// 如果是列表中点击的
					mToBePlayedSongPos = intent.getIntExtra(
							"playing_position_in_list", 0);
				}
				processPlayRequest();
			}
		} else if (action.equals(ACTION_PAUSE)) {
			processPauseRequest();
		} else if (action.equals(ACTION_STOP)) {
			processStopRequest();
		} else if (action.equals(ACTION_PREVIOUS)) {
			if (mHasPlayList) {
				processPreviousRequest(true);
			}
		} else if (action.equals(ACTION_NEXT)) {
			if (mHasPlayList) {
				processNextRequest(true);
			}
		}
		return START_NOT_STICKY; // 当本服务被系统关闭后，不必再重启启动
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return mBinder;
	}

	@Override
	public void onDestroy() {
		// Service关闭时释放所有持有的资源
		mState = State.Stopped;
		relaxResources(true);
		giveUpAudioFocus();
	}

	/**
	 * 确保MediaPlayer存在，并且已经被重置。 这个方法将会在需要时创建一个MediaPlayer，
	 * 或者重置一个已存在的MediaPlayer。
	 */
	void createMediaPlayerIfNeeded() {
		if (mPlayer == null) {
			mPlayer = new MediaPlayer();

			// 确保我们的MediaPlayer在播放时获取了一个唤醒锁，
			// 如果不这样做，当歌曲播放很久时，CPU进入休眠从而导致播放停止
			// 要使用唤醒锁，要确保在AndroidManifest.xml中声明了android.permission.WAKE_LOCK权限
			mPlayer.setWakeMode(getApplicationContext(),
					PowerManager.PARTIAL_WAKE_LOCK);

			// 在MediaPlayer在它准备完成时、完成播放时、发生错误时通过监听器通知我们，
			// 以便我们做出相应处理
			mPlayer.setOnPreparedListener(this);
			mPlayer.setOnCompletionListener(this);
			mPlayer.setOnErrorListener(this);
		} else
			mPlayer.reset();
	}

	void processPlayRequest() {
		tryToGetAudioFocus();

		// 如果处于“停止”状态，直接播放下一首歌曲
		// 如果处于“播放”或者“暂停”状态，并且请求播放的歌曲与当前播放的歌曲不同，则播放请求的歌曲
		if (mState == State.Stopped
				|| ((mState == State.Paused || mState == State.Playing) && mPlayList
						.get(mPlayingSongPos).getId() != mPlayList.get(
						mToBePlayedSongPos).getId())) {
			mPlayingSongPos = mToBePlayedSongPos;
			playSong();
		} else if (mState == State.Paused
				&& mPlayList.get(mPlayingSongPos).getId() == mPlayList.get(
						mToBePlayedSongPos).getId()) {
			// 如果处于“暂停”状态，则继续播放，并且恢复“前台服务”的状态
			mState = State.Playing;
			setUpAsForeground(mPlayList.get(mPlayingSongPos).getTitle()
					+ " (playing)");
			configAndStartMediaPlayer();
		} else if (mPlayer.isLooping()) {
			mPlayer.start();
		}

		// 通知所有的观察者音乐开始播放了
		for (int i = 0; i < mOnPlaybackStateChangeListeners.size(); i++) {
			mOnPlaybackStateChangeListeners.get(i).onMusicPlayed();
		}

		// 更新进度条
		if (!mServiceHandler.hasMessages(MESSAGE_UPDATE_PLAYING_SONG_PROGRESS)) {
			mServiceHandler
					.sendEmptyMessage(MESSAGE_UPDATE_PLAYING_SONG_PROGRESS);
		}
	}

	void processPauseRequest() {
		if (mState == State.Playing) {
			mState = State.Paused;
			mPlayer.pause();
			relaxResources(false); // 暂停时，取消“前台服务”的状态，但依然保持MediaPlayer的资源
			// 仍然保持着音频焦点

			// 通知所有的观察者音乐暂停了
			for (int i = 0; i < mOnPlaybackStateChangeListeners.size(); i++) {
				mOnPlaybackStateChangeListeners.get(i).onMusicPaused();
			}
		}
	}

	/**
	 * 播放上一首歌曲。根据播放模式计算出上一首歌的ID，然后调用播放方法。
	 * 
	 * @param fromUser
	 *            是否是来自用户的请求
	 * */
	void processPreviousRequest(boolean fromUser) {
		if (mState == State.Playing || mState == State.Paused
				|| mState == State.Stopped) {
			switch (mPlayMode) {
			case PLAYMODE_REPEAT:
			case PLAYMODE_SEQUENTIAL:
				if (--mToBePlayedSongPos < 0) {
					mToBePlayedSongPos = mPlayList.size() - 1;
				}
				break;
			case PLAYMODE_SHUFFLE:
				if (mPlayQueue.size() != 0) {
					mToBePlayedSongPos = mPlayQueue.pop();
				} else {
					mToBePlayedSongPos = mRandom.nextInt(mPlayList.size());
				}
				break;
			case PLAYMODE_REPEAT_SINGLE:
				if (fromUser) {
					// 如果是用户请求播放上一首，就顺序播放上一首
					if (--mToBePlayedSongPos < 0) {
						mToBePlayedSongPos = mPlayList.size() - 1;
					}
				} else {
					// 如果不是用户请求，循环播放
					mPlayer.setLooping(true);
				}
			default:
				break;
			}
			processPlayRequest();
		}
	}

	/**
	 * 播放下一首歌曲。根据播放模式计算出下一首歌的ID，然后调用播放方法
	 * 
	 * @param fromUser
	 *            是否是来自用户的请求
	 * */
	void processNextRequest(boolean fromUser) {
		if (mState == State.Playing || mState == State.Paused
				|| mState == State.Stopped) {
			switch (mPlayMode) {
			case PLAYMODE_REPEAT:
				mToBePlayedSongPos = (mToBePlayedSongPos + 1)
						% mPlayList.size();
				break;
			case PLAYMODE_SEQUENTIAL:
				mToBePlayedSongPos = (mToBePlayedSongPos + 1)
						% mPlayList.size();
				if (mToBePlayedSongPos == 0) {
					if (fromUser) {
						mToBePlayedSongPos = 0;
					} else {
						// 播放到当前播放列表的最后一首便停止播放
						mToBePlayedSongPos = mPlayList.size() - 1;
						processStopRequest();
						return;
					}
				}
				break;
			case PLAYMODE_SHUFFLE:
				mPlayQueue.push(mToBePlayedSongPos);
				mToBePlayedSongPos = mRandom.nextInt(mPlayList.size());
				break;
			case PLAYMODE_REPEAT_SINGLE:
				if (fromUser) {
					// 如果是用户请求，就顺序播放下一首
					mToBePlayedSongPos = (mToBePlayedSongPos + 1)
							% mPlayList.size();
				} else {
					// 如果不是用户请求，循环播放
					mPlayer.setLooping(true);
				}
			default:
				break;
			}
			processPlayRequest();
		}
	}

	void processStopRequest() {
		processStopRequest(false);
	}

	void processStopRequest(boolean force) {
		if (mState == State.Playing || mState == State.Paused || force) {
			mState = State.Stopped;

			// 释放所有持有的资源
			relaxResources(true);
			giveUpAudioFocus();

			// 本服务已经不再使用，终结它
			// stopSelf();

			// 通知所有的观察者音乐停止播放了
			for (int i = 0; i < mOnPlaybackStateChangeListeners.size(); i++) {
				mOnPlaybackStateChangeListeners.get(i).onMusicStopped();
			}
		}
	}

	/**
	 * 释放本服务所使用的资源，包括“前台服务”状态，通知，唤醒锁，和MediaPlayer
	 * 
	 * @param releaseMediaPlayer
	 *            指示MediaPlayer是否要释放掉
	 */
	void relaxResources(boolean releaseMediaPlayer) {
		// 取消 "foreground service"的状态
		stopForeground(true);

		// 停止并释放MediaPlayer
		if (releaseMediaPlayer && mPlayer != null) {
			mPlayer.reset();
			mPlayer.release();
			mPlayer = null;
		}

		// 如果持有Wifi锁，也将其释放
		if (mWifiLock.isHeld())
			mWifiLock.release();
	}

	/** 放弃音频焦点的持有 */
	void giveUpAudioFocus() {
		if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.abandonFocus())
			mAudioFocus = AudioFocus.NoFocusNoDuck;
	}

	/**
	 * 根据音频焦点的设置重新设置MediaPlayer的参数，然后启动或者重启它。 如果我们拥有音频焦点，则正常播放;
	 * 如果没有音频焦点，根据当前的焦点设置将MediaPlayer切换为“暂停”状态或者低声播放。
	 * 这个方法已经假设mPlayer不为空，所以如果要调用此方法，确保正确的使用它。
	 */
	void configAndStartMediaPlayer() {
		if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
			// 如果丢失了音频焦点也不允许低声播放，我们必须让播放暂停，即使mState处于State.Playing状态。
			// 但是我们并不修改mState的状态，因为我们会在获得音频焦点时返回立即返回播放状态。
			if (mPlayer.isPlaying())
				mPlayer.pause();
			return;
		} else if (mAudioFocus == AudioFocus.NoFocusCanDuck)
			mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME); // 设置一个较为安静的音量
		else
			mPlayer.setVolume(1.0f, 1.0f); // 设置大声播放

		if (!mPlayer.isPlaying())
			mPlayer.start();
	}

	/** 尝试获取音频焦点 */
	void tryToGetAudioFocus() {
		if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.requestFocus())
			mAudioFocus = AudioFocus.Focused;
	}

	/**
	 * 播放mPlayingSongPos指定的歌曲.
	 */
	void playSong() {
		mState = State.Stopped;
		relaxResources(false); // 除了MediaPlayer，释放所有资源

		try {
			if (mHasPlayList) {
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(getApplicationContext(), ContentUris
						.withAppendedId(Media.EXTERNAL_CONTENT_URI, mPlayList
								.get(mPlayingSongPos).getId()));
			} else {
				processStopRequest(true);
				return;
			}

			mState = State.Preparing;
			setUpAsForeground(mPlayList.get(mPlayingSongPos).getTitle()
					+ " (loading)");

			// 在后台准备MediaPlayer，准备完成后会调用OnPreparedListener的onPrepared()方法。
			// 在MediaPlayer准备好之前，我们不能调用其start()方法
			mPlayer.prepareAsync();

			// 如果正在从网络获取歌曲，我们要持有一个Wifi锁，以防止在音乐播放时系统休眠暂停了音乐。
			// 另一方面，如果没有从网络获取歌曲，并且持有一个Wifi锁，我们要将它释放掉。
			if (mIsStreaming)
				mWifiLock.acquire();
			else if (mWifiLock.isHeld())
				mWifiLock.release();
		} catch (Exception ex) {
			Log.e("MusicService",
					"IOException playing next song: " + ex.getMessage());
			ex.printStackTrace();
		}

		// 每次播放新的歌曲的时候，把当前播放的歌曲信息传递给播放观察者
		for (int i = 0; i < mOnPlaybackStateChangeListeners.size(); i++) {
			mOnPlaybackStateChangeListeners.get(i).onPlayNewSong(
					mPlayList.get(mPlayingSongPos));
		}
	}

	/** 更新通知栏. */
	void updateNotification(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0, new Intent(getApplicationContext(),
						MusicPlayerActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification.setLatestEventInfo(getApplicationContext(),
				"LQMusicPlayer", text, pi);
		mNotificationManager.notify(NOTIFICATION_ID, mNotification);
	}

	/**
	 * 将本服务设置为“前台服务”。“前台服务”是一个与用户正在交互的服务， 必须在通知栏显示一个通知表示正在交互
	 */
	void setUpAsForeground(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0, new Intent(getApplicationContext(),
						MusicPlayerActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification = new Notification();
		mNotification.tickerText = text;
		mNotification.icon = R.drawable.ic_stat_playing;
		mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
		mNotification.setLatestEventInfo(getApplicationContext(),
				getResources().getString(R.string.app_name), text, pi);
		startForeground(NOTIFICATION_ID, mNotification);
	}

	/** MediaPlayer完成了一首歌曲的播放时调用此方法 */
	public void onCompletion(MediaPlayer player) {
		// 当前播放完成，播放下一首
		processNextRequest(false);
	}

	/** MediaPlayer完成了准备时调用此方法 */
	public void onPrepared(MediaPlayer player) {
		// 准备完成了，可以播放歌曲了
		mState = State.Playing;
		updateNotification(mPlayList.get(mPlayingSongPos).getTitle()
				+ " (playing)");
		configAndStartMediaPlayer();
		if (!mServiceHandler.hasMessages(MESSAGE_UPDATE_PLAYING_SONG_PROGRESS)) {
			mServiceHandler
					.sendEmptyMessage(MESSAGE_UPDATE_PLAYING_SONG_PROGRESS);
		}
	}

	/**
	 * 播放媒体时发生了错误调用此方法,错误发生后停止播放.
	 */
	public boolean onError(MediaPlayer mp, int what, int extra) {
		Toast.makeText(getApplicationContext(),
				"Media player error! Resetting.", Toast.LENGTH_SHORT).show();
		switch (what) {
		case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
			Log.e(TAG, "Error: "
					+ "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK"
					+ ", extra=" + String.valueOf(extra));
			break;
		case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
			Log.e(TAG, "Error: " + "MEDIA_ERROR_SERVER_DIED" + ", extra="
					+ String.valueOf(extra));
			break;
		case MediaPlayer.MEDIA_ERROR_UNKNOWN:
			Log.e(TAG,
					"Error: " + "MEDIA_ERROR_UNKNOWN" + ", extra="
							+ String.valueOf(extra));
			break;
		case -38:
			Log.e(TAG,
					"Error: what"
							+ what
							+ ", extra="
							+ extra
							+ ",see at http://blog.sina.com.cn/s/blog_632b619d01012991.html");
			break;
		default:
			Log.e(TAG, "Error: what" + what + ", extra=" + extra);
			break;
		}

		processStopRequest(false);

		return true; // true表示我们处理了发生的错误
	}

	public void onGainedAudioFocus() {
		Toast.makeText(getApplicationContext(), "gained audio focus.",
				Toast.LENGTH_SHORT).show();
		mAudioFocus = AudioFocus.Focused;

		// 用新的音频焦点状态来重置MediaPlayer
		if (mState == State.Playing)
			configAndStartMediaPlayer();
	}

	public void onLostAudioFocus(boolean canDuck) {
		Toast.makeText(getApplicationContext(),
				"lost audio focus." + (canDuck ? "can duck" : "no duck"),
				Toast.LENGTH_SHORT).show();
		mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck
				: AudioFocus.NoFocusNoDuck;

		// 以新的焦点参数启动/重启/暂停MediaPlayer
		if (mPlayer != null && mPlayer.isPlaying())
			configAndStartMediaPlayer();
	}

	public State getPlayState() {
		return mState;
	}

	public void seekToSpecifiedPosition(int milliSeconds) {
		if (mState != State.Stopped) {
			mPlayer.seekTo(milliSeconds);
		}
	}

	public void changePlayMode() {
		mPlayMode = (mPlayMode + 1) % 4;
		if (mPlayer != null) {
			switch (mPlayMode) {
			case PLAYMODE_REPEAT_SINGLE:
				mPlayer.setLooping(true);
				break;
			default:
				mPlayer.setLooping(false);
				break;
			}
		}

		for (int i = 0; i < mOnPlaybackStateChangeListeners.size(); i++) {
			mOnPlaybackStateChangeListeners.get(i).onPlayModeChanged(mPlayMode);
		}
	}

	public void setCurrentPlayList(List<MusicItem> list) {
		mPlayList.clear();
		mPlayList.addAll(list);
		mHasPlayList = true;
		mToBePlayedSongPos = 0;
	}
}