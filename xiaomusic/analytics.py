"""统计模块（已彻底禁用所有外部数据上报）"""


class Analytics:
    def __init__(self, log, config):
        self.log = log
        self.config = config

    def init(self):
        pass

    async def send_startup_event(self):
        pass

    async def send_daily_event(self):
        pass

    async def send_play_event(self, name, sec, hardware):
        pass

    async def _send(self, event):
        pass

    async def post_to_umami(self, event):
        pass
