# EC2 CI/CD Deployment

This repository deploys to EC2 through `.github/workflows/deploy-ec2.yml`.

## Trigger

- Every push to `main`
- Manual `workflow_dispatch`

## GitHub Secrets

Required repository secrets:

- `EC2_HOST`: EC2 public DNS name.
- `EC2_USER`: SSH user, currently `ec2-user`.
- `EC2_SSH_PRIVATE_KEY`: private SSH key used to access the instance.
- `KALSHI_KEY_ID`: Kalshi API key ID.
- `KALSHI_PRIVATE_KEY`: Kalshi RSA private key content.

## GitHub Variables

Recommended repository variables:

- `DEPLOY_PATH`: remote app path, default `/opt/group_08_project`.
- `DEPLOY_PROFILE`: Docker Compose profile, default `cluster-live`.
- `KALSHI_BASE_URL`: default `https://api.elections.kalshi.com`.
- `KALSHI_MARKET_SERIES_TICKER`: default `KXHIGHCHI`.
- `KALSHI_WS_CHANNELS`: default `orderbook_delta,trade,ticker,market_lifecycle_v2`.

Optional:

- `KALSHI_MARKET_TICKERS`: comma-separated explicit market ticker list. If unset, the live client resolves open markets from `KALSHI_MARKET_SERIES_TICKER`.

## EC2 Bootstrap

The workflow bootstraps an Amazon Linux host by installing:

- `git`
- `docker`
- Docker Compose plugin

It then clones or resets the repo at `DEPLOY_PATH`, writes runtime secrets into `DEPLOY_PATH/secrets`, writes `.env`, and starts the configured Docker Compose profile.

## Operational Commands

On the EC2 instance:

```bash
cd /opt/group_08_project
sudo docker compose --env-file .env --profile cluster-live ps
sudo docker compose --env-file .env --profile cluster-live logs -f --tail=200
sudo docker compose --env-file .env --profile cluster-live down
```

## Security Notes

The Kalshi private key and runtime `.env` are written only on the EC2 host and are not committed to the repository. The GitHub Actions workflow keeps the SSH key and Kalshi key in repository secrets.
