const toggle = document.querySelector('.menu-toggle');
const nav = document.querySelector('.nav');

toggle?.addEventListener('click', () => {
  const open = nav.classList.toggle('open');
  toggle.setAttribute('aria-expanded', String(open));
  toggle.textContent = open ? '关闭' : '菜单';
});

nav?.addEventListener('click', (event) => {
  if (event.target.closest('a')) {
    nav.classList.remove('open');
    toggle?.setAttribute('aria-expanded', 'false');
    if (toggle) toggle.textContent = '菜单';
  }
});

const demos = {
  process: {
    code: `<span class="code-comment">// 创建一个可恢复的后台进程</span>
worker = process.fork(<span class="code-string">"invoice-worker"</span>, {
  invoice = queue.next()
  result = network.webget(invoice.url)
  file.write(<span class="code-string">"/reports/latest.json"</span>, result.body)
})

<span class="code-keyword">return</span> worker.pid`,
    output: 'process 0017 · READY'
  },
  timer: {
    code: `<span class="code-comment">// 挂起当前进程，计时器本身持久化</span>
deadline = time.now() + 60000
timer.sleepUntil(deadline)

io.println(<span class="code-string">"一分钟后，我仍会醒来"</span>)
<span class="code-keyword">return</span> deadline`,
    output: 'timer 0082 · SCHEDULED'
  },
  ipc: {
    code: `<span class="code-comment">// 消息先提交，然后唤醒接收者</span>
ipc.send(worker.pid, {
  type: <span class="code-string">"invoice.ready"</span>,
  id: invoice.id
})

reply = ipc.receive()
<span class="code-keyword">return</span> reply.status`,
    output: 'delivery 0241 · COMMITTED'
  }
};

const code = document.querySelector('#demo-code');
const output = document.querySelector('#demo-output');
const outputState = document.querySelector('.output-state');
const runButton = document.querySelector('.run-button');
let activeDemo = 'process';

document.querySelectorAll('.code-tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    activeDemo = tab.dataset.demo;
    document.querySelectorAll('.code-tab').forEach((item) => {
      const active = item === tab;
      item.classList.toggle('active', active);
      item.setAttribute('aria-selected', String(active));
    });
    code.innerHTML = demos[activeDemo].code;
    output.textContent = 'ready to execute';
    outputState.className = 'output-state';
  });
});

runButton?.addEventListener('click', () => {
  runButton.disabled = true;
  output.textContent = 'committing execution slice…';
  outputState.className = 'output-state running';
  window.setTimeout(() => {
    output.textContent = demos[activeDemo].output;
    outputState.className = 'output-state done';
    runButton.disabled = false;
  }, 650);
});
