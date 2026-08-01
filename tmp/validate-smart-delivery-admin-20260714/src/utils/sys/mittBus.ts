import mitt, { type Emitter } from 'mitt';
type Events = {
    triggerFireworks: string | undefined;
    openSetting: void;
    openSearchDialog: void;
    openChat: void;
    openLockScreen: void;
};
const mittBus: Emitter<Events> = mitt<Events>();
export default mittBus;
