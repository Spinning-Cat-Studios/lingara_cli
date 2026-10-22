// The command tree, registered once (oclif's `explicit` strategy): oclif's
// config, the kit's dispatch and `__tree` all read this table, and `make
// check-tree` diffs it against spec/cli.schema.json. Ids use `:` between
// words; `topicSeparator: " "` is how a developer types them.
import AppsCreate from './commands/apps/create.js'
import AppsDelete from './commands/apps/delete.js'
import AppsDev from './commands/apps/dev.js'
import AppsGet from './commands/apps/get.js'
import AppsList from './commands/apps/list.js'
import AppsPin from './commands/apps/pin.js'
import AppsStatus from './commands/apps/status.js'
import AppsSubmit from './commands/apps/submit.js'
import AppsUnpin from './commands/apps/unpin.js'
import AppsUpdate from './commands/apps/update.js'
import AppsVersionsList from './commands/apps/versions/list.js'
import AppsVersionsPublish from './commands/apps/versions/publish.js'
import AppsVersionsWithdraw from './commands/apps/versions/withdraw.js'
import CanvasApply from './commands/canvas/apply.js'
import CanvasCreate from './commands/canvas/create.js'
import CanvasDelete from './commands/canvas/delete.js'
import CanvasDraft from './commands/canvas/draft.js'
import CanvasGet from './commands/canvas/get.js'
import CanvasList from './commands/canvas/list.js'
import CanvasUpdate from './commands/canvas/update.js'
import EmbedCreate from './commands/embed/create.js'
import EmbedDelete from './commands/embed/delete.js'
import EmbedGet from './commands/embed/get.js'
import EmbedList from './commands/embed/list.js'
import EmbedUpdate from './commands/embed/update.js'
import Login from './commands/login.js'
import Logout from './commands/logout.js'
import OauthClientCreate from './commands/oauth_client/create.js'
import OauthClientDelete from './commands/oauth_client/delete.js'
import OauthClientGet from './commands/oauth_client/get.js'
import OauthClientList from './commands/oauth_client/list.js'
import OauthClientSecretCreate from './commands/oauth_client/secret/create.js'
import OauthClientSecretDelete from './commands/oauth_client/secret/delete.js'
import OauthClientUpdate from './commands/oauth_client/update.js'
import Tree from './commands/tree.js'
import WebhookInboundCreate from './commands/webhook/inbound/create.js'
import WebhookInboundDelete from './commands/webhook/inbound/delete.js'
import WebhookInboundGet from './commands/webhook/inbound/get.js'
import WebhookInboundList from './commands/webhook/inbound/list.js'
import WebhookInboundUpdate from './commands/webhook/inbound/update.js'
import WebhookOutboundCreate from './commands/webhook/outbound/create.js'
import WebhookOutboundDelete from './commands/webhook/outbound/delete.js'
import WebhookOutboundGet from './commands/webhook/outbound/get.js'
import WebhookOutboundList from './commands/webhook/outbound/list.js'
import WebhookOutboundReplace from './commands/webhook/outbound/replace.js'
import WebhookOutboundTest from './commands/webhook/outbound/test.js'
import WebhookOutboundUpdate from './commands/webhook/outbound/update.js'
import Whoami from './commands/whoami.js'

export const COMMANDS = {
  login: Login,
  logout: Logout,
  whoami: Whoami,
  __tree: Tree,
  'apps:create': AppsCreate,
  'apps:list': AppsList,
  'apps:get': AppsGet,
  'apps:update': AppsUpdate,
  'apps:dev': AppsDev,
  'apps:status': AppsStatus,
  'apps:pin': AppsPin,
  'apps:unpin': AppsUnpin,
  'apps:delete': AppsDelete,
  'apps:submit': AppsSubmit,
  'apps:versions:list': AppsVersionsList,
  'apps:versions:publish': AppsVersionsPublish,
  'apps:versions:withdraw': AppsVersionsWithdraw,
  'embed:create': EmbedCreate,
  'embed:list': EmbedList,
  'embed:get': EmbedGet,
  'embed:update': EmbedUpdate,
  'embed:delete': EmbedDelete,
  'oauth_client:create': OauthClientCreate,
  'oauth_client:list': OauthClientList,
  'oauth_client:get': OauthClientGet,
  'oauth_client:update': OauthClientUpdate,
  'oauth_client:delete': OauthClientDelete,
  'oauth_client:secret:create': OauthClientSecretCreate,
  'oauth_client:secret:delete': OauthClientSecretDelete,
  'webhook:inbound:create': WebhookInboundCreate,
  'webhook:inbound:list': WebhookInboundList,
  'webhook:inbound:get': WebhookInboundGet,
  'webhook:inbound:update': WebhookInboundUpdate,
  'webhook:inbound:delete': WebhookInboundDelete,
  'webhook:outbound:create': WebhookOutboundCreate,
  'webhook:outbound:list': WebhookOutboundList,
  'webhook:outbound:get': WebhookOutboundGet,
  'webhook:outbound:update': WebhookOutboundUpdate,
  'webhook:outbound:replace': WebhookOutboundReplace,
  'webhook:outbound:test': WebhookOutboundTest,
  'webhook:outbound:delete': WebhookOutboundDelete,
  'canvas:create': CanvasCreate,
  'canvas:list': CanvasList,
  'canvas:get': CanvasGet,
  'canvas:update': CanvasUpdate,
  'canvas:draft': CanvasDraft,
  'canvas:apply': CanvasApply,
  'canvas:delete': CanvasDelete,
}
