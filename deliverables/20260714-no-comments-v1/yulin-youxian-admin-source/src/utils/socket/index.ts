interface WebSocketOptions {
    url?: string;
    messageHandler: (event: MessageEvent) => void;
    reconnectInterval?: number;
    heartbeatInterval?: number;
    pingInterval?: number;
    reconnectTimeout?: number;
    maxReconnectAttempts?: number;
    connectionTimeout?: number;
}
export default class WebSocketClient {
    private static instance: WebSocketClient | null = null;
    private ws: WebSocket | null = null;
    private url: string;
    private messageHandler: (event: MessageEvent) => void;
    private reconnectInterval: number;
    private heartbeatInterval: number;
    private pingInterval: number;
    private reconnectTimeout: number;
    private maxReconnectAttempts: number;
    private connectionTimeout: number;
    private reconnectAttempts: number = 0;
    private messageQueue: Array<string | ArrayBufferLike | Blob | ArrayBufferView> = [];
    private detectionTimer: NodeJS.Timeout | null = null;
    private timeoutTimer: NodeJS.Timeout | null = null;
    private reconnectTimer: NodeJS.Timeout | null = null;
    private pingTimer: NodeJS.Timeout | null = null;
    private connectionTimer: NodeJS.Timeout | null = null;
    private isConnected: boolean = false;
    private isConnecting: boolean = false;
    private stopReconnect: boolean = false;
    private constructor(options: WebSocketOptions) {
        this.url = options.url || (process.env.VUE_APP_LOGIN_WEBSOCKET as string);
        this.messageHandler = options.messageHandler;
        this.reconnectInterval = options.reconnectInterval || 20 * 1000;
        this.heartbeatInterval = options.heartbeatInterval || 5 * 1000;
        this.pingInterval = options.pingInterval || 10 * 1000;
        this.reconnectTimeout = options.reconnectTimeout || 30 * 1000;
        this.maxReconnectAttempts = options.maxReconnectAttempts || 10;
        this.connectionTimeout = options.connectionTimeout || 10 * 1000;
    }
    static getInstance(options: WebSocketOptions): WebSocketClient {
        if (!WebSocketClient.instance) {
            WebSocketClient.instance = new WebSocketClient(options);
        }
        else {
            WebSocketClient.instance.messageHandler = options.messageHandler;
            if (options.url && WebSocketClient.instance.url !== options.url) {
                WebSocketClient.instance.url = options.url;
                WebSocketClient.instance.reconnectAttempts = 0;
                WebSocketClient.instance.init();
            }
        }
        return WebSocketClient.instance;
    }
    init(): void {
        if (this.isConnecting) {
            console.log('正在建立WebSocket连接中...');
            return;
        }
        if (this.ws?.readyState === WebSocket.OPEN) {
            console.warn('WebSocket连接已存在');
            this.flushMessageQueue();
            return;
        }
        try {
            this.isConnecting = true;
            this.reconnectAttempts = 0;
            this.ws = new WebSocket(this.url);
            this.clearTimer('connectionTimer');
            this.connectionTimer = setTimeout(() => {
                console.error(`WebSocket连接超时 (${this.connectionTimeout}ms)：${this.url}`);
                this.handleConnectionTimeout();
            }, this.connectionTimeout);
            this.ws.onopen = (event) => this.handleOpen(event);
            this.ws.onmessage = (event) => this.handleMessage(event);
            this.ws.onclose = (event) => this.handleClose(event);
            this.ws.onerror = (event) => this.handleError(event);
        }
        catch (error) {
            console.error('WebSocket初始化失败:', error);
            this.isConnecting = false;
            this.reconnect();
        }
    }
    private handleConnectionTimeout(): void {
        if (this.ws?.readyState !== WebSocket.OPEN) {
            console.error('WebSocket连接超时，强制关闭连接');
            this.ws?.close(1000, 'Connection timeout');
            this.isConnecting = false;
            this.reconnect();
        }
    }
    close(force?: boolean): void {
        this.clearAllTimers();
        this.stopReconnect = true;
        this.isConnecting = false;
        if (this.ws) {
            this.ws.close(force ? 1001 : 1000, force ? 'Force closed' : 'Normal close');
            this.ws = null;
        }
        this.isConnected = false;
    }
    send(data: string | ArrayBufferLike | Blob | ArrayBufferView, immediate: boolean = false): void {
        if (immediate && (!this.ws || this.ws.readyState !== WebSocket.OPEN)) {
            console.error('WebSocket未连接，无法立即发送消息');
            return;
        }
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            console.log('WebSocket未连接，消息已加入队列等待发送');
            this.messageQueue.push(data);
            if (!this.isConnecting && !this.stopReconnect) {
                this.init();
            }
            return;
        }
        try {
            this.ws.send(data);
        }
        catch (error) {
            console.error('WebSocket发送消息失败:', error);
            this.messageQueue.push(data);
            this.reconnect();
        }
    }
    private flushMessageQueue(): void {
        if (this.messageQueue.length > 0 && this.ws?.readyState === WebSocket.OPEN) {
            console.log(`发送队列中的${this.messageQueue.length}条消息`);
            while (this.messageQueue.length > 0) {
                const data = this.messageQueue.shift();
                if (data) {
                    try {
                        this.ws?.send(data);
                    }
                    catch (error) {
                        console.error('发送队列消息失败:', error);
                        if (data)
                            this.messageQueue.unshift(data);
                        break;
                    }
                }
            }
        }
    }
    private handleOpen(event: Event): void {
        console.log('WebSocket连接成功', event);
        this.clearTimer('connectionTimer');
        this.isConnected = true;
        this.isConnecting = false;
        this.stopReconnect = false;
        this.reconnectAttempts = 0;
        this.startHeartbeat();
        this.startPing();
        this.flushMessageQueue();
    }
    private handleMessage(event: MessageEvent): void {
        console.log('收到WebSocket消息:', event);
        this.resetHeartbeat();
        this.messageHandler(event);
    }
    private handleClose(event: CloseEvent): void {
        console.log(`WebSocket断开: 代码=${event.code}, 原因=${event.reason}, 干净关闭=${event.wasClean}`);
        const isNormalClose = event.code === 1000;
        this.isConnected = false;
        this.isConnecting = false;
        this.clearAllTimers();
        if (!this.stopReconnect && !isNormalClose) {
            this.reconnect();
        }
    }
    private handleError(event: Event): void {
        console.error('WebSocket连接错误:');
        console.error('错误事件:', event);
        console.error('当前连接状态:', this.ws?.readyState ? this.getReadyStateText(this.ws.readyState) : '未初始化');
        this.isConnected = false;
        this.isConnecting = false;
        if (!this.stopReconnect) {
            this.reconnect();
        }
    }
    private getReadyStateText(state: number): string {
        switch (state) {
            case WebSocket.CONNECTING:
                return 'CONNECTING (0) - 正在连接';
            case WebSocket.OPEN:
                return 'OPEN (1) - 已连接';
            case WebSocket.CLOSING:
                return 'CLOSING (2) - 正在关闭';
            case WebSocket.CLOSED:
                return 'CLOSED (3) - 已关闭';
            default:
                return `未知状态 (${state})`;
        }
    }
    private startHeartbeat(): void {
        this.clearTimer('detectionTimer');
        this.clearTimer('timeoutTimer');
        this.detectionTimer = setTimeout(() => {
            this.isConnected = this.ws?.readyState === WebSocket.OPEN;
            if (!this.isConnected) {
                console.warn('WebSocket心跳检测失败，尝试重连');
                this.reconnect();
                this.timeoutTimer = setTimeout(() => {
                    console.warn('WebSocket重连超时');
                    this.close();
                }, this.reconnectTimeout);
            }
        }, this.heartbeatInterval);
    }
    private resetHeartbeat(): void {
        this.clearTimer('detectionTimer');
        this.clearTimer('timeoutTimer');
        this.startHeartbeat();
    }
    private startPing(): void {
        this.clearTimer('pingTimer');
        this.pingTimer = setInterval(() => {
            if (this.ws?.readyState !== WebSocket.OPEN) {
                console.warn('WebSocket未连接，停止发送ping');
                this.clearTimer('pingTimer');
                this.reconnect();
                return;
            }
            try {
                this.ws.send('ping');
                console.log('发送ping消息');
            }
            catch (error) {
                console.error('发送ping消息失败:', error);
                this.clearTimer('pingTimer');
                this.reconnect();
            }
        }, this.pingInterval);
    }
    private reconnect(): void {
        if (this.stopReconnect || this.isConnecting) {
            return;
        }
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error(`已达到最大重连次数(${this.maxReconnectAttempts})，停止重连`);
            this.close(true);
            return;
        }
        this.reconnectAttempts++;
        this.stopReconnect = true;
        this.close(true);
        const delay = this.calculateReconnectDelay();
        console.log(`将在${delay / 1000}秒后尝试重新连接（第${this.reconnectAttempts}/${this.maxReconnectAttempts}次）`);
        this.clearTimer('reconnectTimer');
        this.reconnectTimer = setTimeout(() => {
            console.log(`尝试重新连接WebSocket（第${this.reconnectAttempts}次）`);
            this.init();
            this.stopReconnect = false;
        }, delay);
    }
    private calculateReconnectDelay(): number {
        const jitter = Math.random() * 1000;
        const baseDelay = Math.min(this.reconnectInterval * Math.pow(1.5, this.reconnectAttempts - 1), this.reconnectInterval * 5);
        return baseDelay + jitter;
    }
    private clearTimer(timerName: 'detectionTimer' | 'timeoutTimer' | 'reconnectTimer' | 'pingTimer' | 'connectionTimer'): void {
        if (this[timerName]) {
            clearTimeout(this[timerName] as NodeJS.Timeout);
            this[timerName] = null;
        }
    }
    private clearAllTimers(): void {
        this.clearTimer('detectionTimer');
        this.clearTimer('timeoutTimer');
        this.clearTimer('reconnectTimer');
        this.clearTimer('pingTimer');
        this.clearTimer('connectionTimer');
    }
    get isWebSocketConnected(): boolean {
        return this.isConnected;
    }
    get connectionStatusText(): string {
        if (this.isConnecting)
            return '正在连接';
        if (this.isConnected)
            return '已连接';
        if (this.reconnectAttempts > 0 && !this.stopReconnect)
            return `重连中（${this.reconnectAttempts}/${this.maxReconnectAttempts}）`;
        return '已断开';
    }
    static destroyInstance(): void {
        if (WebSocketClient.instance) {
            WebSocketClient.instance.close();
            WebSocketClient.instance = null;
        }
    }
}
