const menuToggle = document.querySelector('.menu-toggle');
const mainNav = document.querySelector('.nav');

menuToggle?.addEventListener('click', () => {
  const open = mainNav.classList.toggle('open');
  menuToggle.setAttribute('aria-expanded', String(open));
  menuToggle.textContent = open ? '关闭' : '菜单';
});

mainNav?.addEventListener('click', (event) => {
  if (!event.target.closest('a')) return;
  mainNav.classList.remove('open');
  menuToggle?.setAttribute('aria-expanded', 'false');
  if (menuToggle) menuToggle.textContent = '菜单';
});

const searchInput = document.querySelector('#syntax-search');
const cards = [...document.querySelectorAll('.syntax-card')];
const visibleCount = document.querySelector('#visible-count');
const emptyState = document.querySelector('#syntax-empty');
const clearSearch = document.querySelector('#clear-search');

function filterSyntax() {
  const query = searchInput.value.trim().toLocaleLowerCase('zh-CN');
  let count = 0;
  cards.forEach((card) => {
    const searchable = `${card.dataset.search || ''} ${card.textContent}`.toLocaleLowerCase('zh-CN');
    const visible = !query || searchable.includes(query);
    card.hidden = !visible;
    if (visible) count += 1;
  });
  visibleCount.textContent = String(count);
  emptyState.hidden = count !== 0;
  document.querySelector('.syntax-rail').classList.toggle('searching', Boolean(query));
}

searchInput?.addEventListener('input', filterSyntax);
clearSearch?.addEventListener('click', () => {
  searchInput.value = '';
  filterSyntax();
  searchInput.focus();
});

document.addEventListener('keydown', (event) => {
  if (event.key === '/' && document.activeElement !== searchInput) {
    event.preventDefault();
    searchInput.focus();
  }
  if (event.key === 'Escape' && document.activeElement === searchInput) {
    searchInput.value = '';
    filterSyntax();
    searchInput.blur();
  }
});

document.querySelectorAll('.copy-code').forEach((button) => {
  button.addEventListener('click', async () => {
    const code = document.getElementById(button.dataset.copyTarget);
    try {
      await navigator.clipboard.writeText(code.innerText);
      button.textContent = '已复制';
      button.classList.add('copied');
      window.setTimeout(() => {
        button.textContent = '复制';
        button.classList.remove('copied');
      }, 1400);
    } catch {
      button.textContent = '选择代码复制';
    }
  });
});

const railLinks = [...document.querySelectorAll('#syntax-nav a')];
const observer = new IntersectionObserver((entries) => {
  const visible = entries
    .filter((entry) => entry.isIntersecting && !entry.target.hidden)
    .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
  if (!visible) return;
  railLinks.forEach((link) => {
    link.classList.toggle('active', link.getAttribute('href') === `#${visible.target.id}`);
  });
}, {rootMargin: '-18% 0px -70% 0px', threshold: 0});

cards.forEach((card) => observer.observe(card));
