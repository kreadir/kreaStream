version = 2

cloudstream {
    authors     = listOf("kreaStream")
    language    = "tr"
    description = "Canlı Dizi için Cloudstream eklentisi"
    
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.canlidizi14.com/favicon.ico"
}
